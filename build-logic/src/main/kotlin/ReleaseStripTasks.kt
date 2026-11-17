package com.payabli.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipInputStream

/**
 * Writes a variant's classes into one jar, without the members marked `@StrippedFromRelease` and the classes
 * they enclose. Every other entry is copied byte for byte.
 */
abstract class StripReleaseClasses : DefaultTask() {
    /** The variant's class jars. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jars: ListProperty<RegularFile>

    /** The variant's class directories. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val directories: ListProperty<Directory>

    /** The one jar the variant packages instead. */
    @get:OutputFile
    abstract val output: RegularFileProperty

    /** Reads every class once to plan the strip, then writes the jar. */
    @TaskAction
    fun strip() {
        val entries = linkedMapOf<String, ByteArray>()
        for (jar in jars.get()) {
            JarFile(jar.asFile).use { file ->
                for (entry in file.entries()) {
                    if (!entry.isDirectory) entries.putIfAbsent(entry.name, file.getInputStream(entry).readBytes())
                }
            }
        }
        for (directory in directories.get()) {
            val root = directory.asFile
            root.walkTopDown().filter(File::isFile).forEach { file ->
                entries.putIfAbsent(file.relativeTo(root).invariantSeparatorsPath, file.readBytes())
            }
        }

        val facts = entries.filterKeys { it.endsWith(".class") }.mapValues { readFacts(it.value) }
        val plan = planStrip(facts.values)
        logger.info("Stripped from release: ${plan.methods.size} methods, ${plan.classes.size} classes")

        JarOutputStream(output.get().asFile.outputStream().buffered()).use { out ->
            for ((name, bytes) in entries) {
                val fact = facts[name]
                if (fact != null && fact.name in plan.classes) continue
                out.putNextEntry(JarEntry(name))
                out.write(if (fact != null) stripClass(bytes, plan) else bytes)
                out.closeEntry()
            }
        }
    }
}

/**
 * Fails when the AAR still carries a marked member, or when any of its classes calls a method of its own that
 * the AAR does not declare. A marked member that production code calls fails here, rather than as a
 * `NoSuchMethodError` in an integrator's app.
 */
abstract class VerifyReleaseStrip : DefaultTask() {
    /** The release AAR. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aar: RegularFileProperty

    /** Members the module names as never shipping, as `binary/class/Name.member`; see [ReleaseStripExtension]. */
    @get:Input
    abstract val absent: ListProperty<String>

    /** Reads `classes.jar` out of the AAR and checks every class in it. */
    @TaskAction
    fun verify() {
        val classes = readAarClasses(aar.get().asFile)
        val problems = findStripProblems(classes) + findPresent(classes, absent.get())
        if (problems.isNotEmpty()) {
            throw GradleException("The release AAR is not stripped:\n" + problems.joinToString("\n") { "  $it" })
        }
    }
}

/**
 * Where a module names the members its release must not carry, so a hook that lost its marker fails the check
 * instead of shipping.
 */
abstract class ReleaseStripExtension {
    /** Each as `binary/class/Name.member`, the member's source name without the JVM's suffixes. */
    abstract val absent: ListProperty<String>
}

/**
 * Lists each of [absent] that [classes] still declare. A member matches by its source name, so the JVM's
 * `$core` and value-class suffixes and a `$default` bridge all count.
 *
 * @param classes every class file in the artifact.
 * @param absent the members, as `binary/class/Name.member`.
 * @return one line per member still present.
 */
internal fun findPresent(
    classes: List<ByteArray>,
    absent: List<String>,
): List<String> {
    val declared =
        classes.map(::readFacts).flatMap { fact ->
            fact.methods.map { "${it.owner}.${it.name.split('$', '-').first()}" }
        }.toSet()
    return absent.filter { it in declared }.map { "still present: $it" }
}

/**
 * Reads `classes.jar` out of an AAR.
 *
 * @param aar the archive.
 * @return each class file's bytes.
 */
internal fun readAarClasses(aar: File): List<ByteArray> {
    val jar =
        JarFile(aar).use { file ->
            val entry = file.getJarEntry("classes.jar") ?: throw GradleException("$aar has no classes.jar")
            file.getInputStream(entry).readBytes()
        }
    val classes = mutableListOf<ByteArray>()
    ZipInputStream(jar.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.forEach { entry ->
            if (entry.name.endsWith(".class")) classes += zip.readBytes()
        }
    }
    return classes
}

private const val OBJECT = "java/lang/Object"

/** `java.lang.Object`'s methods as name plus descriptor, read from this JVM's own class. */
private val objectMethods: Set<String> =
    Any::class.java.declaredMethods.map { it.name + Type.getMethodDescriptor(it) }.toSet() +
        "<init>()V"

/**
 * Lists what keeps [classes] from being a stripped release: a marked member still present, and a call to a
 * method of a class in the set that neither that class nor any of its supertypes in the set declares.
 *
 * @param classes every class file in the artifact.
 * @return one line per problem, empty when there is none.
 */
internal fun findStripProblems(classes: List<ByteArray>): List<String> {
    val facts = classes.map(::readFacts).associateBy { it.name }
    val supertypes = classes.associate { bytes -> ClassReader(bytes).let { it.className to listOfNotNull(it.superName) + it.interfaces } }
    val problems = mutableListOf<String>()

    facts.values.flatMap { it.marked }.forEach { problems += "still marked: ${it.owner}.${it.name}${it.descriptor}" }

    fun declares(
        owner: String,
        name: String,
        descriptor: String,
    ): Boolean {
        if (owner == OBJECT) return "$name$descriptor" in objectMethods
        // Any other supertype outside the artifact may declare it, and this check cannot read that class.
        val fact = facts[owner] ?: return true
        if (fact.methods.any { it.name == name && it.descriptor == descriptor }) return true
        return supertypes[owner].orEmpty().any { declares(it, name, descriptor) }
    }

    for (bytes in classes) {
        val reader = ClassReader(bytes)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val caller = "${reader.className}.$name"
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            calledName: String,
                            calledDescriptor: String,
                            isInterface: Boolean,
                        ) {
                            if (!declares(owner, calledName, calledDescriptor)) {
                                problems += "$caller calls $owner.$calledName$calledDescriptor, which is not in the artifact"
                            }
                        }
                    }
                }
            },
            ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
        )
    }
    return problems.distinct()
}
