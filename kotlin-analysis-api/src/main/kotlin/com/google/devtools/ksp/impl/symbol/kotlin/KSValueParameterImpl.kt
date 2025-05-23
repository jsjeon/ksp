/*
 * Copyright 2022 Google LLC
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.google.devtools.ksp.impl.symbol.kotlin

import com.google.devtools.ksp.common.KSObjectCache
import com.google.devtools.ksp.common.impl.KSNameImpl
import com.google.devtools.ksp.common.lazyMemoizedSequence
import com.google.devtools.ksp.impl.symbol.kotlin.resolved.KSTypeReferenceResolvedImpl
import com.google.devtools.ksp.symbol.*
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.analysis.api.symbols.KaValueParameterSymbol
import org.jetbrains.kotlin.analysis.api.types.abbreviationOrSelf
import org.jetbrains.kotlin.fir.symbols.SymbolInternals

class KSValueParameterImpl private constructor(
    private val ktValueParameterSymbol: KaValueParameterSymbol,
    override val parent: KSAnnotated
) : KSValueParameter, Deferrable {
    companion object : KSObjectCache<KaValueParameterSymbol, KSValueParameterImpl>() {
        fun getCached(ktValueParameterSymbol: KaValueParameterSymbol, parent: KSAnnotated) =
            cache.getOrPut(ktValueParameterSymbol) { KSValueParameterImpl(ktValueParameterSymbol, parent) }
    }

    override val name: KSName? by lazy {
        if (origin == Origin.SYNTHETIC && parent is KSPropertySetter) {
            KSNameImpl.getCached("value")
        } else {
            KSNameImpl.getCached(ktValueParameterSymbol.name.asString())
        }
    }

    @OptIn(SymbolInternals::class)
    override val type: KSTypeReference by lazy {
        // TODO: avoid eager resolution by using PSI.
        // KaFirValueParameterSymbol extracts and returns the element type of a vararg.
        // That logic needs to be replicated if we resolve the PSI via
        // analyze { KtTypeReference.type }.
        KSTypeReferenceResolvedImpl.getCached(
            ktValueParameterSymbol.returnType.abbreviationOrSelf,
            this@KSValueParameterImpl
        )
    }

    override val isVararg: Boolean by lazy {
        ktValueParameterSymbol.isVararg
    }

    override val isNoInline: Boolean
        get() = ktValueParameterSymbol.isNoinline

    override val isCrossInline: Boolean
        get() = ktValueParameterSymbol.isCrossinline

    private val KaValueParameterSymbol.primaryConstructorProperty: KaPropertySymbol? by lazy {
        when (ktValueParameterSymbol.origin) {
            // ktValueParameterSymbol.generatedPrimaryConstructorProperty is always null in libraries.
            // TODO: fix in AA
            KaSymbolOrigin.LIBRARY, KaSymbolOrigin.JAVA_LIBRARY -> analyze {
                val cstr = ktValueParameterSymbol.containingDeclaration as? KaConstructorSymbol
                val cls = cstr?.containingDeclaration as? KaClassSymbol
                cls?.declaredMemberScope?.declarations?.filterIsInstance<KaPropertySymbol>()
                    ?.firstOrNull { it.name == ktValueParameterSymbol.name }
            }

            else -> ktValueParameterSymbol.generatedPrimaryConstructorProperty
        }
    }

    override val isVal: Boolean
        get() = ktValueParameterSymbol.primaryConstructorProperty?.isVal == true

    override val isVar: Boolean
        get() = ktValueParameterSymbol.primaryConstructorProperty?.isVal == false

    override val hasDefault: Boolean by lazy {
        ktValueParameterSymbol.hasDefaultValue
    }

    override val annotations: Sequence<KSAnnotation> by lazyMemoizedSequence {
        ktValueParameterSymbol.annotations(this)
    }
    override val origin: Origin by lazy {
        val symbolOrigin = mapAAOrigin(ktValueParameterSymbol)
        if (symbolOrigin == Origin.KOTLIN && ktValueParameterSymbol.psi == null) {
            Origin.SYNTHETIC
        } else {
            symbolOrigin
        }
    }

    override val location: Location by lazy {
        ktValueParameterSymbol.psi?.toLocation() ?: NonExistLocation
    }

    override fun <D, R> accept(visitor: KSVisitor<D, R>, data: D): R {
        return visitor.visitValueParameter(this, data)
    }

    override fun toString(): String {
        return name?.asString() ?: "_"
    }

    override fun defer(): Restorable? {
        val other = (parent as Deferrable).defer() ?: return null
        return ktValueParameterSymbol.defer inner@{
            getCached(it, other.restore() ?: return@inner null)
        }
    }
}
