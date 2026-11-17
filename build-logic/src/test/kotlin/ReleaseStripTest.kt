package com.payabli.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

/** What `payabli.release-strip` removes, what it keeps, and what its check refuses. */
class ReleaseStripTest {
    private val subject = "p/Subject"
    private val lambda = "p/Subject\$hook\$1"
    private val nested = "p/Subject\$hook\$1\$1"

    @Test
    fun `a marked method goes with its default bridge and every class it encloses`() {
        val plan = planStrip(fixture().map(::readFacts))

        assertEquals(
            setOf(MethodRef(subject, "hook\$core", "()V"), MethodRef(subject, "hook\$core\$default", "(I)V")),
            plan.methods,
        )
        assertEquals(setOf(lambda, nested), plan.classes)
    }

    @Test
    fun `the stripped class keeps what is unmarked and stops naming what went`() {
        val all = fixture()
        val plan = planStrip(all.map(::readFacts))

        val stripped = readFacts(stripClass(all.first(), plan))

        assertEquals(listOf("kept"), stripped.methods.map { it.name })
        assertEquals(emptySet<MethodRef>(), stripped.marked)
    }

    @Test
    fun `the check passes a stripped artifact and refuses an unstripped one`() {
        val all = fixture()
        val plan = planStrip(all.map(::readFacts))
        val shipped = all.filter { readFacts(it).name !in plan.classes }.map { stripClass(it, plan) }

        assertEquals(emptyList<String>(), findStripProblems(shipped))
        assertTrue(findStripProblems(all).any { it.startsWith("still marked: $subject.hook\$core()V") })
    }

    @Test
    fun `the check refuses a call to a member the artifact no longer has`() {
        val all = fixture()
        val plan = planStrip(all.map(::readFacts))
        val shipped =
            all.filter { readFacts(it).name !in plan.classes }.map { stripClass(it, plan) } +
                caller(owner = subject, name = "hook\$core")

        assertEquals(
            listOf("p/Caller.run calls $subject.hook\$core()V, which is not in the artifact"),
            findStripProblems(shipped),
        )
    }

    @Test
    fun `the check refuses a reference to a class the strip removed`() {
        val all = fixture()
        val plan = planStrip(all.map(::readFacts))
        val stripped = all.filter { readFacts(it).name !in plan.classes }.map { stripClass(it, plan) }
        val constructs =
            type("p/Caller") {
                visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).apply {
                    visitCode()
                    visitTypeInsn(Opcodes.NEW, lambda)
                    visitInsn(Opcodes.RETURN)
                    visitMaxs(1, 1)
                    visitEnd()
                }
            }

        assertEquals(
            listOf("p/Caller.run names $lambda, which is not in the artifact"),
            findStripProblems(stripped + constructs),
        )
        assertEquals(
            listOf("p/Caller.run calls $lambda.invoke()V, which is not in the artifact"),
            findStripProblems(stripped + caller(owner = lambda, name = "invoke")),
        )
    }

    @Test
    fun `a method the class inherits from Object is not a missing member`() {
        val shipped = listOf(type(subject) {}, caller(owner = subject, name = "hashCode", descriptor = "()I"))

        assertEquals(emptyList<String>(), findStripProblems(shipped))
    }

    @Test
    fun `a member named as absent fails the check while any form of it ships`() {
        val absent = listOf("$subject.hook", "$subject.kept")
        val all = fixture()
        val plan = planStrip(all.map(::readFacts))

        // Unstripped: the hook ships, so it is reported, as is the member that ships in every case.
        assertEquals(listOf("still present: $subject.hook", "still present: $subject.kept"), findPresent(all, absent))
        assertEquals(listOf("still present: $subject.kept"), findPresent(all.map { stripClass(it, plan) }, absent))
    }

    @Test
    fun `a member named with its descriptor matches that overload only`() {
        val shipped =
            listOf(
                type(subject) {
                    method("<init>")
                    method("<init>", "(Lp/Lock;)V")
                },
            )

        assertEquals(
            listOf("still present: $subject.<init>(Lp/Lock;)V"),
            findPresent(shipped, listOf("$subject.<init>(Lp/Lock;)V", "$subject.<init>(I)V")),
        )
    }

    /** The subject, a lambda its hook encloses, a class that lambda encloses. */
    private fun fixture(): List<ByteArray> =
        listOf(
            type(subject) {
                method("kept")
                method("hook\$core").visitAnnotation("Lp/StrippedFromRelease;", false)
                method("hook\$core\$default", "(I)V", Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC)
                visitInnerClass(lambda, null, null, Opcodes.ACC_FINAL)
            },
            type(lambda) {
                visitOuterClass(subject, "hook\$core", "()V")
                method("invoke")
            },
            type(nested) {
                visitOuterClass(lambda, null, null)
                method("invoke")
            },
        )

    private fun caller(
        owner: String,
        name: String,
        descriptor: String = "()V",
    ): ByteArray =
        type("p/Caller") {
            visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null).apply {
                visitCode()
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, name, descriptor, false)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
        }

    private fun type(
        name: String,
        body: ClassWriter.() -> Unit,
    ): ByteArray =
        ClassWriter(0)
            .apply {
                visit(Opcodes.V11, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
                body()
                visitEnd()
            }.toByteArray()

    private fun ClassWriter.method(
        name: String,
        descriptor: String = "()V",
        access: Int = Opcodes.ACC_PUBLIC,
    ) = visitMethod(access, name, descriptor, null, null)
}
