package com.payabli.buildlogic

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * The marker's simple name. Matched by suffix, so each module declares its own `internal` annotation and none of
 * them has to be visible to another module.
 */
internal const val MARKER_SUFFIX = "/StrippedFromRelease;"

/** One method, as the class file names it. */
internal data class MethodRef(
    val owner: String,
    val name: String,
    val descriptor: String,
)

/** What one class file declares and where it sits, read without its code. */
internal class ClassFacts(
    val name: String,
    val outerClass: String?,
    val outerMethod: MethodRef?,
    val methods: List<MethodRef>,
    val marked: Set<MethodRef>,
)

/**
 * Reads [bytes] without their code.
 *
 * @param bytes one class file.
 * @return the class's name, what encloses it, its methods and the marked ones among them.
 */
internal fun readFacts(bytes: ByteArray): ClassFacts {
    var name = ""
    var outerClass: String? = null
    var outerMethod: MethodRef? = null
    val methods = mutableListOf<MethodRef>()
    val marked = mutableSetOf<MethodRef>()
    ClassReader(bytes).accept(
        object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                className: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?,
            ) {
                name = className
            }

            override fun visitOuterClass(
                owner: String,
                methodName: String?,
                methodDescriptor: String?,
            ) {
                outerClass = owner
                if (methodName != null && methodDescriptor != null) {
                    outerMethod = MethodRef(owner, methodName, methodDescriptor)
                }
            }

            override fun visitInnerClass(
                innerName: String,
                outerName: String?,
                simpleName: String?,
                access: Int,
            ) {
                // A member class names its outer one here rather than in an EnclosingMethod attribute.
                if (innerName == name && outerName != null && outerClass == null) outerClass = outerName
            }

            override fun visitMethod(
                access: Int,
                methodName: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val ref = MethodRef(name, methodName, descriptor)
                methods += ref
                return object : MethodVisitor(Opcodes.ASM9) {
                    override fun visitAnnotation(
                        annotation: String,
                        visible: Boolean,
                    ): AnnotationVisitor? {
                        if (annotation.endsWith(MARKER_SUFFIX)) marked += ref
                        return null
                    }
                }
            }
        },
        ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES,
    )
    return ClassFacts(name, outerClass, outerMethod, methods, marked)
}

/** What the strip removes from one set of classes. */
internal class StripPlan(
    val methods: Set<MethodRef>,
    val classes: Set<String>,
)

/**
 * Works out what goes: every marked method, the `$default` bridge Kotlin writes beside one, and every class a
 * removed method encloses — its lambdas and its coroutine state — down to the classes those enclose.
 *
 * @param facts every class the variant packages.
 * @return the methods and classes to remove.
 */
internal fun planStrip(facts: Collection<ClassFacts>): StripPlan {
    val methods = mutableSetOf<MethodRef>()
    for (fact in facts) {
        methods += fact.marked
        val bridges = fact.marked.map { "${it.name}\$default" }.toSet()
        methods += fact.methods.filter { it.name in bridges }
    }
    val classes = mutableSetOf<String>()
    var grew = true
    while (grew) {
        grew = false
        for (fact in facts) {
            if (fact.name in classes) continue
            if (fact.outerMethod in methods || fact.outerClass in classes) {
                classes += fact.name
                grew = true
            }
        }
    }
    return StripPlan(methods, classes)
}

/**
 * Rewrites one class without the methods [plan] removes and without naming the classes it removes.
 *
 * @param bytes the class file.
 * @param plan what goes.
 * @return the class file as it ships.
 */
internal fun stripClass(
    bytes: ByteArray,
    plan: StripPlan,
): ByteArray {
    val reader = ClassReader(bytes)
    val writer = ClassWriter(reader, 0)
    reader.accept(
        object : ClassVisitor(Opcodes.ASM9, writer) {
            private var name = ""

            override fun visit(
                version: Int,
                access: Int,
                className: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?,
            ) {
                name = className
                super.visit(version, access, className, signature, superName, interfaces)
            }

            override fun visitInnerClass(
                innerName: String,
                outerName: String?,
                simpleName: String?,
                access: Int,
            ) {
                if (innerName !in plan.classes) super.visitInnerClass(innerName, outerName, simpleName, access)
            }

            override fun visitMethod(
                access: Int,
                methodName: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? =
                if (MethodRef(name, methodName, descriptor) in plan.methods) {
                    null
                } else {
                    super.visitMethod(access, methodName, descriptor, signature, exceptions)
                }
        },
        0,
    )
    return writer.toByteArray()
}
