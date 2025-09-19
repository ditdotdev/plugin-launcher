package io.titandata.plugin

import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec

class StructUtilTest : StringSpec() {
    val util = StructUtil()

    init {
        "convert basic map succeeds" {
            val src = mapOf("a" to "b")
            val res = util.structToMap(util.mapToStruct(src))
            res.size shouldBe 1
            res["a"] shouldBe "b"
        }

        "convert nested map succeeds" {
            val src = mapOf("a" to mapOf("b" to "c"))
            val res = util.structToMap(util.mapToStruct(src))
            res.size shouldBe 1
            @Suppress("UNCHECKED_CAST")
            val child = res["a"] as Map<String, String>
            child.size shouldBe 1
            child["b"] shouldBe "c"
        }

        "convert list succeeds" {
            val src = mapOf("a" to listOf("b", "c"))
            val res = util.structToMap(util.mapToStruct(src))
            res.size shouldBe 1
            @Suppress("UNCHECKED_CAST")
            val child = res["a"] as List<String>
            child.size shouldBe 2
            child[0] shouldBe "b"
            child[1] shouldBe "c"
        }

        "convert non-string types succeeds" {
            val src = mapOf("bool" to true, "int" to 4, "float" to 4.0)
            val res = util.structToMap(util.mapToStruct(src))
            res.size shouldBe 3
            res["bool"] shouldBe true
            res["int"] shouldBe 4.0
            res["float"] shouldBe 4.0
        }
    }
}
