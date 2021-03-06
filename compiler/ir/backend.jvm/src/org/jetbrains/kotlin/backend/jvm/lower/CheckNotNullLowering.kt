/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irIfThen
import org.jetbrains.kotlin.backend.common.phaser.makeIrFilePhase
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue

internal val checkNotNullPhase = makeIrFilePhase(
    ::CheckNotNullLowering,
    name = "CheckNotNullLowering",
    description = "Lower calls to the CHECK_NOT_NULL intrinsic, which are generated by psi2ir for \"!!\" expressions."
)

private class CheckNotNullLowering(private val backendContext: JvmBackendContext) : FileLoweringPass, IrElementTransformerVoidWithContext() {
    override fun lower(irFile: IrFile) = irFile.transformChildrenVoid()

    override fun visitCall(expression: IrCall): IrExpression {
        if (expression.symbol != backendContext.irBuiltIns.checkNotNullSymbol)
            return super.visitCall(expression)

        expression.transformChildrenVoid()
        return backendContext.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol, expression.startOffset, expression.endOffset).irBlock {
            val valueArgument = expression.getValueArgument(0)!!

            // For a null-check on a variable we should not introduce a temporary, since null checks are
            // optimized by RedundantNullCheckMethodTransformer, which remembers that the argument variable
            // is non-nullable if this call succeeds.
            val argument = if (valueArgument is IrGetValue) valueArgument.symbol.owner else irTemporary(valueArgument)

            // Starting with Kotlin 1.4 null-checks are lowered to calls to the "checkNotNull" intrinsic, which
            // throws a NullPointerException on failure. Prior to Kotlin 1.4 we instead inline the null-check and
            // call the "throwNpe" intrinsic on failure which throws a KotlinNullPointerException.
            if (backendContext.state.unifiedNullChecks) {
                +irCall(backendContext.ir.symbols.checkNotNull).apply {
                    putValueArgument(0, irGet(argument))
                }
            } else {
                +irIfThen(irEqualsNull(irGet(argument)), irCall(backendContext.ir.symbols.throwNpe))
            }

            +irImplicitCast(irGet(argument), expression.type)
        }
    }
}
