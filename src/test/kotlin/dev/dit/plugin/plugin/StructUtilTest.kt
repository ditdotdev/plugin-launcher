// Copyright Dit 2026
// SPDX-License-Identifier: BUSL-1.1

package dev.dit.plugin

import com.google.protobuf.NullValue
import com.google.protobuf.Struct
import com.google.protobuf.Value
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
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

        "convert empty map succeeds" {
            val src = emptyMap<String, Any>()
            val res = util.structToMap(util.mapToStruct(src))
            res.size shouldBe 0
        }

        "convert empty list succeeds" {
            val src = mapOf("empty" to emptyList<Any>())
            val res = util.structToMap(util.mapToStruct(src))
            res.size shouldBe 1
            @Suppress("UNCHECKED_CAST")
            val child = res["empty"] as List<Any>
            child.size shouldBe 0
        }

        "convert deeply nested structure succeeds" {
            val src = mapOf("l1" to mapOf("l2" to mapOf("l3" to "deep")))
            val res = util.structToMap(util.mapToStruct(src))

            @Suppress("UNCHECKED_CAST")
            val l1 = res["l1"] as Map<String, Any>

            @Suppress("UNCHECKED_CAST")
            val l2 = l1["l2"] as Map<String, Any>
            l2["l3"] shouldBe "deep"
        }

        "convert list of mixed types succeeds" {
            val src = mapOf("mixed" to listOf("hello", 42, true, 3.14))
            val res = util.structToMap(util.mapToStruct(src))

            @Suppress("UNCHECKED_CAST")
            val child = res["mixed"] as List<Any>
            child.size shouldBe 4
            child[0] shouldBe "hello"
            child[1] shouldBe 42.0
            child[2] shouldBe true
            child[3] shouldBe 3.14
        }

        "convert list of maps succeeds" {
            val src = mapOf("items" to listOf(mapOf("k" to "v1"), mapOf("k" to "v2")))
            val res = util.structToMap(util.mapToStruct(src))

            @Suppress("UNCHECKED_CAST")
            val items = res["items"] as List<Map<String, Any>>
            items.size shouldBe 2
            items[0]["k"] shouldBe "v1"
            items[1]["k"] shouldBe "v2"
        }

        "mapToStruct throws for unsupported type" {
            shouldThrow<IllegalArgumentException> {
                util.mapToStruct(mapOf("bad" to java.util.Date()))
            }
        }

        "mapToStruct throws for null in list" {
            shouldThrow<IllegalArgumentException> {
                util.mapToStruct(mapOf("list" to listOf("a", null, "b")))
            }
        }

        "convert multiple keys preserves all entries" {
            val src = mapOf("a" to "1", "b" to "2", "c" to "3", "d" to "4")
            val res = util.structToMap(util.mapToStruct(src))
            res.size shouldBe 4
            res["a"] shouldBe "1"
            res["b"] shouldBe "2"
            res["c"] shouldBe "3"
            res["d"] shouldBe "4"
        }

        "convert float type preserves precision" {
            val src = mapOf("pi" to 3.14159f)
            val res = util.structToMap(util.mapToStruct(src))
            // Float is converted to Double
            val value = res["pi"] as Double
            (value > 3.14 && value < 3.15) shouldBe true
        }

        "mapToStruct rejects nested map with non-String keys" {
            // Validates the type check that replaced the previous unchecked cast.
            val bad: Map<String, Any> = mapOf("outer" to mapOf(1 to "v"))
            shouldThrow<IllegalArgumentException> {
                util.mapToStruct(bad)
            }
        }

        "structToMap throws for unsupported value kind (NULL_VALUE)" {
            // valueToNative's else-branch fires for any kind not explicitly mapped
            // (STRUCT_VALUE, LIST_VALUE, NUMBER_VALUE, STRING_VALUE, BOOL_VALUE).
            // NULL_VALUE is a real proto kind that hits the else.
            val struct =
                Struct
                    .newBuilder()
                    .putFields("k", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build())
                    .build()
            shouldThrow<IllegalArgumentException> {
                util.structToMap(struct)
            }
        }

        "mapToStruct rejects nested map with null value" {
            // The compiler needs the outer map declared as Map<String, Any> for
            // mapToStruct, but the inner map intentionally contains a null value
            // to exercise the validation path that replaced the unchecked cast.
            val innerWithNull: Map<String, Any?> = mapOf("k" to null)
            val bad: Map<String, Any> = mapOf("outer" to innerWithNull)
            shouldThrow<IllegalArgumentException> {
                util.mapToStruct(bad)
            }
        }
    }
}
