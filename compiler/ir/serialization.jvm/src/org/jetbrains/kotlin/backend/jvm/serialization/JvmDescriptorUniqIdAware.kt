/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.serialization

import org.jetbrains.kotlin.backend.common.serialization.DescriptorUniqIdAware
import org.jetbrains.kotlin.backend.common.serialization.tryGetExtension
import org.jetbrains.kotlin.builtins.functions.FunctionClassDescriptor
import org.jetbrains.kotlin.builtins.functions.FunctionInvokeDescriptor
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.load.java.descriptors.JavaClassConstructorDescriptor
import org.jetbrains.kotlin.load.java.descriptors.JavaClassDescriptor
import org.jetbrains.kotlin.load.java.descriptors.JavaMethodDescriptor
import org.jetbrains.kotlin.load.java.descriptors.JavaPropertyDescriptor
import org.jetbrains.kotlin.metadata.jvm.JvmProtoBuf
import org.jetbrains.kotlin.serialization.deserialization.descriptors.*

class JvmDescriptorUniqIdAware(val symbolTable: SymbolTable, val fallback: (IrSymbol) -> IrDeclaration) : DescriptorUniqIdAware {
    override fun DeclarationDescriptor.getUniqId(): Long? =
        when (this) {
            is DeserializedClassDescriptor -> this.classProto.tryGetExtension(JvmProtoBuf.classUniqId)
                ?: referenceAndHash(this)
            is DeserializedSimpleFunctionDescriptor -> this.proto.tryGetExtension(JvmProtoBuf.functionUniqId)
                ?: referenceAndHash(this)
            is DeserializedPropertyDescriptor -> this.proto.tryGetExtension(JvmProtoBuf.propertyUniqId)
                ?: referenceAndHash(this)
            is DeserializedClassConstructorDescriptor -> this.proto.tryGetExtension(JvmProtoBuf.constructorUniqId)
                ?: referenceAndHash(this)
            is DeserializedTypeParameterDescriptor -> this.proto.tryGetExtension(JvmProtoBuf.typeParamUniqId)
                ?: referenceAndHash(this)
            is DeserializedTypeAliasDescriptor -> this.proto.tryGetExtension(JvmProtoBuf.typeAliasUniqId)
                ?: referenceAndHash(this)
            is JavaClassDescriptor,
            is JavaClassConstructorDescriptor,
            is JavaMethodDescriptor,
            is JavaPropertyDescriptor,
            is FunctionClassDescriptor,
            is FunctionInvokeDescriptor -> referenceAndHash(this)
            else -> null
        }

    private fun referenceAndHash(descriptor: DeclarationDescriptor): Long? =
        if (descriptor is CallableMemberDescriptor && descriptor.kind === CallableMemberDescriptor.Kind.FAKE_OVERRIDE)
            null
        else with(JvmMangler) {
            referenceWithParents(descriptor).hashedMangle
        }


    private fun referenceWithParents(descriptor: DeclarationDescriptor): IrDeclaration {
        val original = descriptor.original
        val result = referenceOrDeclare(original)
        var currentDescriptor = original
        var current = result
        while (true) {
            val nextDescriptor = when {
                currentDescriptor is TypeParameterDescriptor && currentDescriptor.containingDeclaration is PropertyDescriptor -> {
                    val property = currentDescriptor.containingDeclaration as PropertyDescriptor
                    // No way to choose between getter and setter by descriptor alone :(
                    property.getter ?: property.setter!!
                }
                else ->
                    currentDescriptor.containingDeclaration!!
            }
            if (nextDescriptor is PackageFragmentDescriptor) {
                current.parent = symbolTable.findOrDeclareExternalPackageFragment(nextDescriptor)
                break
            } else {
                val next = referenceOrDeclare(nextDescriptor)
                current.parent = next as IrDeclarationParent
                currentDescriptor = nextDescriptor
                current = next
            }
        }
        return result
    }

    private fun referenceOrDeclare(descriptor: DeclarationDescriptor): IrDeclaration =
        symbolTable.referenceMember(descriptor).also {
            if (!it.isBound) {
                fallback(it)
            }
        }.owner as IrDeclaration
}

// May be needed in the future
//
//fun DeclarationDescriptor.willBeEliminatedInLowerings(): Boolean =
//        isAnnotationConstructor() ||
//                (this is PropertyAccessorDescriptor &&
//                        correspondingProperty.hasJvmFieldAnnotation())
