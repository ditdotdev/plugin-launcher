/*
 * Copyright The Titan Project Contributors
 */
package io.titandata.plugin

import com.google.protobuf.ListValue
import com.google.protobuf.Struct
import com.google.protobuf.Value

class StructUtil {
    fun structToMap(struct: Struct): Map<String, Any> {
        val ret = mutableMapOf<String, Any>()
        for ((k, v) in struct.fieldsMap) {
            ret[k] = valueToNative(v)
        }
        return ret
    }

    private fun listToNative(list: ListValue): List<Any> {
        val ret = mutableListOf<Any>()
        for (v in list.valuesList) {
            ret.add(valueToNative(v))
        }
        return ret
    }

    private fun valueToNative(value: Value): Any {
        return when (value.kindCase) {
            Value.KindCase.STRUCT_VALUE -> structToMap(value.structValue)
            Value.KindCase.LIST_VALUE -> listToNative(value.listValue)
            Value.KindCase.NUMBER_VALUE -> value.numberValue
            Value.KindCase.STRING_VALUE -> value.stringValue
            Value.KindCase.BOOL_VALUE -> value.boolValue
            else -> throw IllegalArgumentException("unsupported structure value type ${value.kindCase}")
        }
    }

    fun mapToStruct(map: Map<String, Any>): Struct {
        val builder = Value.newBuilder().structValueBuilder
        for ((k, v) in map) {
            builder.putFields(k, nativeToValue(v))
        }
        return builder.build()
    }

    private fun nativeToValue(v: Any): Value {
        val value = Value.newBuilder()
        if (v is String) {
            return value.setStringValue(v).build()
        }
        if (v is Int) {
            return value.setNumberValue(v.toDouble()).build()
        }
        if (v is Float) {
            return value.setNumberValue(v.toDouble()).build()
        }
        if (v is Double) {
            return value.setNumberValue(v).build()
        }
        if (v is Boolean) {
            return value.setBoolValue(v).build()
        }
        if (v is List<*>) {
            val list = value.listValueBuilder
            for (i in v) {
                list.addValues(nativeToValue(i ?: throw IllegalArgumentException("invalid null field in list")))
            }
            return value.setListValue(list).build()
        }
        if (v is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return value.setStructValue(mapToStruct(v as Map<String, Any>)).build()
        }
        throw IllegalArgumentException("Unsupported type: $v")
    }
}
