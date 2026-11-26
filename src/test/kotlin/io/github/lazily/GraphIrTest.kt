package io.github.lazily

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val COUNTER =
    """
    {
      "kind": "graph", "ir": "1", "id": "counter",
      "nodes": [
        {"id": "count", "kind": "source", "init": 1},
        {"id": "doubled", "kind": "computed", "eager": true,
          "expr": {"op": "mul", "args": [{"ref": "count"}, {"lit": 2}]}},
        {"id": "log", "kind": "effect", "body": {"do": "eval", "expr": {"ref": "doubled"}}}
      ]
    }
    """.trimIndent()

private val COUNTER_EDIT =
    """
    {
      "kind": "edit", "ir": "1",
      "ops": [
        {"op": "replace", "node": {"id": "doubled", "kind": "computed",
          "expr": {"op": "add", "args": [{"ref": "count"}, {"lit": 10}]}}},
        {"op": "add", "node": {"id": "label", "kind": "source", "init": "n"}},
        {"op": "add", "node": {"id": "shown", "kind": "computed",
          "expr": {"op": "concat", "args": [{"ref": "label"}, {"lit": ":"}]}}},
        {"op": "remove", "id": "log"}
      ]
    }
    """.trimIndent()

private const val COUNTER_SOURCE =
    "graph counter ir 1\n" +
        "source count = 1\n" +
        "computed doubled eager = mul(ref(count), lit(2))\n" +
        "effect log = eval(ref(doubled))\n"

private fun reads(edges: List<Edge>): List<Edge> = edges.map { Edge(it.from, EdgeKind.READ, it.to) }

private fun failsWith(
    fragment: String,
    body: () -> Unit,
) {
    val err = assertFailsWith<GraphIrError>(block = body)
    assertTrue(err.message!!.contains(fragment), err.message)
}

class GraphIrTest {
    @Test
    fun counterLowersThenAnEditReplacesTheGraph() {
        val doc = GraphIr.fromJsonStr(COUNTER)
        assertEquals("counter", doc.id)
        val doubled = doc.node("doubled")!!
        assertTrue(doubled.eager)
        assertEquals("mul", doubled.expr!!.op)
        assertEquals(
            listOf(Edge("doubled", EdgeKind.READ, "count"), Edge("log", EdgeKind.READ, "doubled")),
            reads(doc.staticEdges()),
        )
        assertEquals(COUNTER_SOURCE, doc.emitSource())
        assertEquals(COUNTER_SOURCE, GraphIr.fromJsonStr(canonicalJson(doc.toJson())).emitSource())

        val rt = GraphRuntime.lower(doc)
        assertEquals(2L, rt.get("doubled"))
        assertEquals(1, rt.effectTrace().size)
        assertEquals("log", rt.effectTrace()[0].id)
        assertEquals(2L, rt.effectTrace()[0].result)
        assertEquals(setOf("count"), rt.observedReads("doubled"))
        assertEquals(setOf("doubled"), rt.observedReads("log"))
        assertTrue(rt.isEager("doubled"))
        assertEquals(Policy.KEEP_LATEST, rt.sourcePolicy("count"))

        val before = rt.emitSource()
        rt.writeSource("count", 4L)
        assertEquals(8L, rt.get("doubled"))
        assertEquals(2, rt.effectTrace().size)
        assertEquals(8L, rt.effectTrace()[1].result)
        assertEquals(before, rt.emitSource())

        rt.apply(GraphEdit.fromJsonStr(COUNTER_EDIT))
        assertEquals(14L, rt.get("doubled"))
        assertEquals("n:", rt.get("shown"))
        assertFalse(rt.contains("log"))
        assertFalse(rt.isEager("doubled"))
        val traceLen = rt.effectTrace().size
        rt.writeSource("count", 5L)
        assertEquals(15L, rt.get("doubled"))
        assertEquals(traceLen, rt.effectTrace().size)
        assertEquals(
            "graph counter ir 1\n" +
                "source count = 1\n" +
                "computed doubled = add(ref(count), lit(10))\n" +
                "source label = \"n\"\n" +
                "computed shown = concat(ref(label), lit(\":\"))\n",
            rt.emitSource(),
        )
    }

    @Test
    fun dynamicIfObservesOnlyTheTakenBranch() {
        val rt =
            GraphRuntime.loadJson(
                """
                {"kind":"graph","ir":"1","nodes":[
                  {"id":"flag","kind":"source","init":false},
                  {"id":"left","kind":"source","init":"L"},
                  {"id":"right","kind":"source","init":"R"},
                  {"id":"picked","kind":"computed","expr":{"op":"if","args":[{"ref":"flag"},{"ref":"left"},{"ref":"right"}]}}
                ]}
                """.trimIndent(),
            )
        assertEquals(
            listOf(
                Edge("picked", EdgeKind.READ, "flag"),
                Edge("picked", EdgeKind.READ, "left"),
                Edge("picked", EdgeKind.READ, "right"),
            ),
            reads(rt.staticEdges()),
        )
        assertEquals("R", rt.get("picked"))
        assertEquals(setOf("flag", "right"), rt.observedReads("picked"))
        rt.writeSource("flag", true)
        assertEquals("L", rt.get("picked"))
        assertEquals(setOf("flag", "left"), rt.observedReads("picked"))
    }

    @Test
    fun sumPolicyFoldsAndSkipsEqualWrite() {
        val rt =
            GraphRuntime.loadJson(
                """
                {"kind":"graph","ir":"1","nodes":[
                  {"id":"tick","kind":"source","init":0},
                  {"id":"total","kind":"source","init":0,"policy":"sum"},
                  {"id":"bump","kind":"effect","body":{"do":"seq","steps":[
                    {"do":"eval","expr":{"ref":"tick"}},
                    {"do":"merge","target":"total","expr":{"lit":1}}
                  ]}}
                ]}
                """.trimIndent(),
            )
        assertTrue(rt.emitSource().contains("policy sum"))
        assertTrue(rt.emitSource().contains("merge(total, lit(1))"))
        assertEquals(1L, rt.get("total"))
        assertEquals("bump", rt.effectTrace()[0].id)
        assertEquals(listOf(0L, 1L), rt.effectTrace()[0].result)
        assertEquals(Policy.SUM, rt.sourcePolicy("total"))
        rt.writeSource("tick", 1L)
        assertEquals(2L, rt.get("total"))
        assertEquals(2, rt.effectTrace().size)
        rt.writeSource("tick", 1L)
        assertEquals(2, rt.effectTrace().size)
        assertEquals(2L, rt.get("total"))
    }

    @Test
    fun structuresReadFieldsLengthAndObjects() {
        val rt =
            GraphRuntime.loadJson(
                """
                {"kind":"graph","ir":"1","nodes":[
                  {"id":"row","kind":"source","init":{"name":"ada","n":2}},
                  {"id":"name","kind":"computed","expr":{"op":"get","args":[{"ref":"row"},{"lit":"name"}]}},
                  {"id":"n","kind":"computed","expr":{"op":"get","args":[{"ref":"row"},{"lit":"n"}]}},
                  {"id":"tag","kind":"computed","expr":{"op":"concat","args":[{"ref":"name"},{"lit":"#"}]}},
                  {"id":"width","kind":"computed","expr":{"op":"len","args":[{"ref":"name"}]}},
                  {"id":"pair","kind":"computed","expr":{"op":"array","args":[{"ref":"name"},{"ref":"n"}]}},
                  {"id":"wrapped","kind":"computed","expr":{"object":[{"key":"name","value":{"ref":"name"}}]}}
                ]}
                """.trimIndent(),
            )
        assertEquals("ada", rt.get("name"))
        assertEquals(2L, rt.get("n"))
        assertEquals("ada#", rt.get("tag"))
        assertEquals(3L, rt.get("width"))
        assertEquals(listOf("ada", 2L), rt.get("pair"))
        assertEquals(mapOf("name" to "ada"), rt.get("wrapped"))
    }

    @Test
    fun cycleAndSnapshotAreRejected() {
        failsWith("cycle in read edges") {
            GraphIr.fromJsonStr(
                """{"kind":"graph","ir":"1","nodes":[{"id":"a","kind":"computed","expr":{"ref":"b"}},{"id":"b","kind":"computed","expr":{"ref":"a"}}]}""",
            )
        }
        failsWith("graph") {
            GraphIr.fromJsonStr("""{"Snapshot":{"epoch":0,"nodes":[],"edges":[],"roots":[]}}""")
        }
    }

    @Test
    fun nullInitAndOrderedBooleanPolicies() {
        val rt =
            GraphRuntime.loadJson(
                """
                {"kind":"graph","ir":"1","nodes":[
                  {"id":"empty","kind":"source","init":null},
                  {"id":"hi","kind":"source","init":3,"policy":"max"},
                  {"id":"lo","kind":"source","init":3,"policy":"min"},
                  {"id":"both","kind":"source","init":true,"policy":"and"},
                  {"id":"either","kind":"source","init":false,"policy":"or"},
                  {"id":"fold","kind":"effect","body":{"do":"seq","steps":[
                    {"do":"merge","target":"hi","expr":{"lit":1}},
                    {"do":"merge","target":"hi","expr":{"lit":5}},
                    {"do":"merge","target":"hi","expr":{"lit":5}},
                    {"do":"merge","target":"lo","expr":{"lit":1}},
                    {"do":"merge","target":"both","expr":{"lit":false}},
                    {"do":"merge","target":"either","expr":{"lit":true}}
                  ]}}
                ]}
                """.trimIndent(),
            )
        assertNull(rt.get("empty"))
        assertEquals(5L, rt.get("hi"))
        assertEquals(1L, rt.get("lo"))
        assertEquals(false, rt.get("both"))
        assertEquals(true, rt.get("either"))
        assertEquals(Policy.MAX, rt.sourcePolicy("hi"))
        assertEquals(Policy.MIN, rt.sourcePolicy("lo"))
        assertEquals(Policy.AND, rt.sourcePolicy("both"))
        assertEquals(Policy.OR, rt.sourcePolicy("either"))
    }

    @Test
    fun andShortCircuitAndDivByZero() {
        val rt =
            GraphRuntime.loadJson(
                """
                {"kind":"graph","ir":"1","nodes":[
                  {"id":"flag","kind":"source","init":false},
                  {"id":"n","kind":"source","init":1},
                  {"id":"bad","kind":"computed","expr":{"op":"div","args":[{"ref":"n"},{"lit":0}]}},
                  {"id":"gate","kind":"computed","expr":{"op":"and","args":[{"ref":"flag"},{"ref":"bad"}]}}
                ]}
                """.trimIndent(),
            )
        assertEquals(false, rt.get("gate"))
        assertEquals(setOf("flag"), rt.observedReads("gate"))
        failsWith("division by zero") { rt.get("bad") }
    }

    @Test
    fun failedEditsLeaveThePreviousGraph() {
        val rt =
            GraphRuntime.loadJson(
                """{"kind":"graph","ir":"1","nodes":[{"id":"a","kind":"source","init":1},{"id":"b","kind":"computed","expr":{"ref":"a"}}]}""",
            )
        failsWith("edit op 0") {
            rt.apply(GraphEdit.fromJsonStr("""{"kind":"edit","ir":"1","ops":[{"op":"remove","id":"a"}]}"""))
        }
        assertTrue(rt.contains("a") && rt.contains("b"))
        assertEquals(1L, rt.get("b"))

        val empty = GraphRuntime.loadJson("""{"kind":"graph","ir":"1","nodes":[]}""")
        failsWith("edit op 0") {
            empty.apply(
                GraphEdit.fromJsonStr(
                    """{"kind":"edit","ir":"1","ops":[{"op":"add","node":{"id":"a","kind":"computed","expr":{"ref":"b"}}},{"op":"add","node":{"id":"b","kind":"source","init":1}}]}""",
                ),
            )
        }
        assertFalse(empty.contains("a") || empty.contains("b"))
        assertEquals("graph ir 1\n", empty.emitSource())
    }

    @Test
    fun replaceSourceResetsLiveValueAndWhenRecordsNull() {
        val rt =
            GraphRuntime.loadJson(
                """{"kind":"graph","ir":"1","id":"g","nodes":[{"id":"count","kind":"source","init":1}]}""",
            )
        rt.writeSource("count", 9L)
        assertEquals(9L, rt.get("count"))
        assertEquals("graph g ir 1\nsource count = 1\n", rt.emitSource())
        rt.apply(
            GraphEdit.fromJsonStr(
                """{"kind":"edit","ir":"1","ops":[{"op":"replace","node":{"id":"count","kind":"source","init":0}}]}""",
            ),
        )
        assertEquals(0L, rt.get("count"))
        assertEquals("graph g ir 1\nsource count = 0\n", rt.emitSource())

        val on =
            GraphRuntime.loadJson(
                """{"kind":"graph","ir":"1","nodes":[{"id":"on","kind":"source","init":true},{"id":"value","kind":"source","init":0},{"id":"apply","kind":"effect","body":{"do":"when","cond":{"ref":"on"},"then":{"do":"set","target":"value","expr":{"lit":7}}}}]}""",
            )
        assertEquals(7L, on.get("value"))
        assertEquals(7L, on.effectTrace()[0].result)
        val off =
            GraphRuntime.loadJson(
                """{"kind":"graph","ir":"1","nodes":[{"id":"on","kind":"source","init":false},{"id":"value","kind":"source","init":0},{"id":"apply","kind":"effect","body":{"do":"when","cond":{"ref":"on"},"then":{"do":"set","target":"value","expr":{"lit":7}}}}]}""",
            )
        assertEquals(0L, off.get("value"))
        assertNull(off.effectTrace()[0].result)
    }

    @Test
    fun emptyGraphAcceptsAddAndRejectionsFailClosed() {
        val rt = GraphRuntime.loadJson("""{"kind":"graph","ir":"1","nodes":[]}""")
        rt.apply(
            GraphEdit.fromJsonStr(
                """{"kind":"edit","ir":"1","ops":[{"op":"add","node":{"id":"x","kind":"source","init":1}}]}""",
            ),
        )
        assertEquals(1L, rt.get("x"))
        assertEquals("graph ir 1\nsource x = 1\n", rt.emitSource())
        for (text in listOf(
            """{"kind":"graph","ir":"2","nodes":[]}""",
            """{"kind":"graph","ir":"1","nodes":[{"id":"1count","kind":"source","init":0}]}""",
            """{"kind":"graph","ir":"1","nodes":[{"id":"c","kind":"computed","expr":{"op":"pow","args":[{"lit":2},{"lit":3}]}}]}""",
            """{"kind":"graph","ir":"1","nodes":[{"id":"e","kind":"effect","body":"log"}]}""",
        )) {
            assertFailsWith<GraphIrError> { GraphIr.fromJsonStr(text) }
        }
        assertFailsWith<GraphIrError> { GraphEdit.fromJsonStr("""{"kind":"edit","ir":"1","ops":[]}""") }
    }

    @Test
    fun feedbackStopsAtTheDrainBudget() {
        val doc =
            GraphIr.fromJsonStr(
                """{"kind":"graph","ir":"1","nodes":[{"id":"n","kind":"source","init":0},{"id":"loop","kind":"effect","body":{"do":"set","target":"n","expr":{"op":"add","args":[{"ref":"n"},{"lit":1}]}}}]}""",
            )
        val rt = GraphRuntime.lower(doc, 4)
        assertEquals(4, rt.lastDrainExhaustion)
        assertEquals(4L, rt.get("n"))
    }
}
