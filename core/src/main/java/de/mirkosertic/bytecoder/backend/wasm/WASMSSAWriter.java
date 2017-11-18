/*
 * Copyright 2017 Mirko Sertic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.mirkosertic.bytecoder.backend.wasm;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import de.mirkosertic.bytecoder.backend.IndentSSAWriter;
import de.mirkosertic.bytecoder.classlib.Address;
import de.mirkosertic.bytecoder.classlib.MemoryManager;
import de.mirkosertic.bytecoder.core.BytecodeLinkedClass;
import de.mirkosertic.bytecoder.core.BytecodeLinkerContext;
import de.mirkosertic.bytecoder.core.BytecodeMethodSignature;
import de.mirkosertic.bytecoder.core.BytecodeObjectTypeRef;
import de.mirkosertic.bytecoder.core.BytecodePrimitiveTypeRef;
import de.mirkosertic.bytecoder.core.BytecodeTypeRef;
import de.mirkosertic.bytecoder.core.BytecodeVirtualMethodIdentifier;
import de.mirkosertic.bytecoder.ssa.BinaryValue;
import de.mirkosertic.bytecoder.ssa.ByteValue;
import de.mirkosertic.bytecoder.ssa.CheckCastExpression;
import de.mirkosertic.bytecoder.ssa.CommentExpression;
import de.mirkosertic.bytecoder.ssa.ComputedMemoryLocationReadValue;
import de.mirkosertic.bytecoder.ssa.ComputedMemoryLocationWriteValue;
import de.mirkosertic.bytecoder.ssa.ControlFlowGraph;
import de.mirkosertic.bytecoder.ssa.DirectInvokeMethodExpression;
import de.mirkosertic.bytecoder.ssa.DirectInvokeMethodValue;
import de.mirkosertic.bytecoder.ssa.Expression;
import de.mirkosertic.bytecoder.ssa.ExpressionList;
import de.mirkosertic.bytecoder.ssa.FixedBinaryValue;
import de.mirkosertic.bytecoder.ssa.FloatValue;
import de.mirkosertic.bytecoder.ssa.GetFieldValue;
import de.mirkosertic.bytecoder.ssa.GetStaticValue;
import de.mirkosertic.bytecoder.ssa.GotoExpression;
import de.mirkosertic.bytecoder.ssa.IFExpression;
import de.mirkosertic.bytecoder.ssa.InitVariableExpression;
import de.mirkosertic.bytecoder.ssa.InlinedNodeExpression;
import de.mirkosertic.bytecoder.ssa.IntegerValue;
import de.mirkosertic.bytecoder.ssa.InvokeStaticMethodExpression;
import de.mirkosertic.bytecoder.ssa.InvokeStaticMethodValue;
import de.mirkosertic.bytecoder.ssa.InvokeVirtualMethodValue;
import de.mirkosertic.bytecoder.ssa.LongValue;
import de.mirkosertic.bytecoder.ssa.NewObjectValue;
import de.mirkosertic.bytecoder.ssa.NullValue;
import de.mirkosertic.bytecoder.ssa.PHIFunction;
import de.mirkosertic.bytecoder.ssa.PrimitiveValue;
import de.mirkosertic.bytecoder.ssa.Program;
import de.mirkosertic.bytecoder.ssa.PutFieldExpression;
import de.mirkosertic.bytecoder.ssa.PutStaticExpression;
import de.mirkosertic.bytecoder.ssa.ReturnExpression;
import de.mirkosertic.bytecoder.ssa.ReturnVariableExpression;
import de.mirkosertic.bytecoder.ssa.SetMemoryLocationExpression;
import de.mirkosertic.bytecoder.ssa.ShortValue;
import de.mirkosertic.bytecoder.ssa.StackStartValue;
import de.mirkosertic.bytecoder.ssa.StackTopValue;
import de.mirkosertic.bytecoder.ssa.ThrowExpression;
import de.mirkosertic.bytecoder.ssa.Type;
import de.mirkosertic.bytecoder.ssa.TypeConversionValue;
import de.mirkosertic.bytecoder.ssa.Value;
import de.mirkosertic.bytecoder.ssa.Variable;
import de.mirkosertic.bytecoder.ssa.VariableReferenceValue;

public class WASMSSAWriter extends IndentSSAWriter {

    public interface IDResolver {

        int resolveVTableMethodByType(BytecodeObjectTypeRef aObjectType);
    }

    private final List<Variable> stackVariables;
    private final IDResolver idResolver;

    public WASMSSAWriter(Program aProgram, String aIndent, PrintWriter aWriter, BytecodeLinkerContext aLinkerContext, IDResolver aIDResolver) {
        super(aProgram, aIndent, aWriter, aLinkerContext);
        stackVariables = new ArrayList<>();
        idResolver = aIDResolver;
        for (Variable theVariable : aProgram.getVariables()) {
            if (theVariable.getType() == Type.REFERENCE) {
                stackVariables.add(theVariable);
            }
        }
    }

    private int stackSize() {
        return stackVariables.size() * 4;
    }

    public boolean isStackVariable(Variable aVariable) {
        for (Variable theVariable : stackVariables) {
            if (theVariable.getName().equals(aVariable.getName())) {
                return true;
            }
        }
        return false;
    }

    private int stackOffsetFor(Variable aVariable) {
        int theStart = stackVariables.size() * 4;
        for (Variable theVariable : stackVariables) {
            if (theVariable.getName().equals(aVariable.getName())) {
                return theStart;
            }
            theStart += 4;
        }
        throw new IllegalStateException("Unknown variable : " + aVariable);
    }

    public WASMSSAWriter withDeeperIndent() {
        return new WASMSSAWriter(program, indent + "    ", writer, linkerContext, idResolver);
    }

    public void writeStartNode(ControlFlowGraph.Node aNode) {
        writeNode(aNode, true);
    }

    private void writeNode(ControlFlowGraph.Node aNode, boolean aStart) {
        if (aNode instanceof ControlFlowGraph.SimpleNode) {

            if (aStart) {
                printStackEnter();
            }

            writeExpressionList(((ControlFlowGraph.SimpleNode) aNode).getNode().getExpressions());
            return;
        }
        if (aNode instanceof ControlFlowGraph.SequenceOfSimpleNodes) {
            ControlFlowGraph.SequenceOfSimpleNodes theSequence = (ControlFlowGraph.SequenceOfSimpleNodes) aNode;

            println("(local $currentLabel i32)");

            if (aStart) {
                printStackEnter();
            }

            println("(set_local $currentLabel (i32.const 0))");
            println("(loop $controlflowloop");

            WASMSSAWriter theChild = withDeeperIndent();
            for (ControlFlowGraph.SimpleNode theJumpTarget : theSequence.getNodes()) {
                theChild.print("(block $");
                theChild.print(theJumpTarget.getNode().getStartAddress().getAddress());
                theChild.println();

                WASMSSAWriter theChild2 = theChild.withDeeperIndent();
                theChild2.print("(br_if $");
                theChild2.print(theJumpTarget.getNode().getStartAddress().getAddress());
                theChild2.println();

                WASMSSAWriter theChild3 = theChild2.withDeeperIndent();
                theChild3.print("(i32.ne (get_local $currentLabel) (i32.const ");
                theChild3.print(theJumpTarget.getNode().getStartAddress().getAddress());
                theChild3.println("))");

                theChild2.println(")");

                theChild2.writeNode(theJumpTarget, false);
                theChild.println(")");
            }

            theChild.println("(br $controlflowloop)");

            println(")");
            println("(unreachable)");
            return;
        }
        throw new IllegalStateException("Not supported!" +  aNode);
    }

    private void writeExpressionList(ExpressionList aList) {
        for (Expression theExpression : aList.toList()) {
            writeExpression(theExpression);
        }
    }

    private void writeExpression(Expression aExpression) {
        if (aExpression instanceof CheckCastExpression) {
            return;
        }
        if (aExpression instanceof ReturnExpression) {
            writeExpression((ReturnExpression) aExpression);
            return;
        }
        if (aExpression instanceof CommentExpression) {
            writeExpression((CommentExpression) aExpression);
            return;
        }
        if (aExpression instanceof InitVariableExpression) {
            writeExpression((InitVariableExpression) aExpression);
            return;
        }
        if (aExpression instanceof DirectInvokeMethodExpression) {
            writeExpression((DirectInvokeMethodExpression) aExpression);
            return;
        }
        if (aExpression instanceof IFExpression) {
            writeExpression((IFExpression) aExpression);
            return;
        }
        if (aExpression instanceof GotoExpression) {
            writeExpression((GotoExpression) aExpression);
            return;
        }
        if (aExpression instanceof InlinedNodeExpression) {
            writeExpression((InlinedNodeExpression) aExpression);
            return;
        }
        if (aExpression instanceof ReturnVariableExpression) {
            writeExpression((ReturnVariableExpression) aExpression);
            return;
        }
        if (aExpression instanceof PutFieldExpression) {
            writeExpression((PutFieldExpression) aExpression);
            return;
        }
        if (aExpression instanceof SetMemoryLocationExpression) {
            writeExpression((SetMemoryLocationExpression) aExpression);
            return;
        }
        if (aExpression instanceof PutStaticExpression) {
            writeExpression((PutStaticExpression) aExpression);
            return;
        }
        if (aExpression instanceof InvokeStaticMethodExpression) {
            writeExpression((InvokeStaticMethodExpression) aExpression);
            return;
        }
        if (aExpression instanceof ThrowExpression) {
            writeExpression((ThrowExpression) aExpression);
            return;
        }
        throw new IllegalStateException("Not supported : " + aExpression);
    }

    private void writeExpression(ThrowExpression aExpression) {
        printStackExit();
        println("(unreachable)");
    }

    private void writeExpression(InvokeStaticMethodExpression aExpression) {
        writeValue(aExpression.getValue());
        println();
    }

    private void writeExpression(PutStaticExpression aExpression) {

        BytecodeLinkedClass theLinkedClass = linkerContext.linkClass(BytecodeObjectTypeRef.fromUtf8Constant(aExpression.getField().getClassIndex().getClassConstant().getConstant()));
        int theMemoryOffset = WASMWriterUtils.computeStaticFieldOffsetOf(aExpression.getField().getNameAndTypeIndex().getNameAndType().getNameIndex().getName().stringValue(),
                theLinkedClass);

        String theClassName = WASMWriterUtils.toClassName(aExpression.getField().getClassIndex().getClassConstant());
        print("(i32.store offset=");
        print(theMemoryOffset);
        println();

        WASMSSAWriter theChild = withDeeperIndent();
        theChild.print("(get_global $");
        theChild.print(theClassName);
        theChild.println("__staticdata)");

        theChild.printVariableNameOrValue(aExpression.getVariable());;

        println(")");
    }

    private void writeExpression(SetMemoryLocationExpression aExpression) {
        println("(i32.store");

        WASMSSAWriter theChild = withDeeperIndent();
        theChild.printVariableNameOrValue(aExpression.getAddress());
        theChild.println();
        theChild.printVariableNameOrValue(aExpression.getValue());
        theChild.println();
        println(")");
    }

    private void writeExpression(PutFieldExpression aExpression) {
        print(";;");
        println("Put field");
    }

    private void writeExpression(InlinedNodeExpression aExpression) {
        writeExpressionList(aExpression.getNode().getExpressions());
    }

    private void writeExpression(GotoExpression aExpression) {
        print("(set_local $currentLabel (i32.const ");
        print(aExpression.getJumpTarget().getAddress());
        println("))");
        println("(br $controlflowloop)");
    }

    private void writeExpression(IFExpression aExpression) {
        aExpression.getBooleanExpression();
        print("(block $");
        print(aExpression.getAddress().getAddress());
        println();

        WASMSSAWriter theChild = withDeeperIndent();

        theChild.print("(br_if $");
        theChild.print(aExpression.getAddress().getAddress());
        theChild.println();

        WASMSSAWriter theChild3 = theChild.withDeeperIndent();
        theChild3.print("(i32.ne ");
        theChild3.printVariableNameOrValue(aExpression.getBooleanExpression());
        theChild3.print(" (i32.const 1)");
        theChild3.println(")");

        theChild.println(")");

        theChild.writeExpressionList(aExpression.getExpressions());

        println(")");
    }

    private void writeExpression(DirectInvokeMethodExpression aExpression) {
        writeValue(aExpression.getValue());
        println();
    }

    private void writeExpression(InitVariableExpression aExpression) {
        Variable theVariable = aExpression.getVariable();
        if (theVariable.getValue() instanceof PrimitiveValue) {
            // Primitives are always inlined!
            return;
        }

        if (theVariable.getValue() instanceof PHIFunction) {
            return;
        }

        if (isStackVariable(theVariable)) {
            print("(i32.store (i32.sub (get_global $STACK) (i32.const ");
            print(stackOffsetFor(theVariable));
            println("))");

            WASMSSAWriter theChild = withDeeperIndent();
            theChild.writeValue(theVariable.getValue());

            println();
            println(")");

        } else {
            print("(set_local $");
            print(theVariable.getName());
            println();

            WASMSSAWriter theChild = withDeeperIndent();
            theChild.writeValue(theVariable.getValue());

            println();
            println(")");
        }
    }

    private void writeValue(Value aValue) {
        if (aValue instanceof BinaryValue) {
            writeValue((BinaryValue) aValue);
            return;
        }
        if (aValue instanceof ByteValue) {
            writeValue((ByteValue) aValue);
            return;
        }
        if (aValue instanceof IntegerValue) {
            writeValue((IntegerValue) aValue);
            return;
        }
        if (aValue instanceof VariableReferenceValue) {
            writeValue((VariableReferenceValue) aValue);
            return;
        }
        if (aValue instanceof DirectInvokeMethodValue) {
            writeValue((DirectInvokeMethodValue) aValue);
            return;
        }
        if (aValue instanceof InvokeStaticMethodValue) {
            writeValue((InvokeStaticMethodValue) aValue);
            return;
        }
        if (aValue instanceof GetFieldValue) {
            writeValue((GetFieldValue) aValue);
            return;
        }
        if (aValue instanceof NewObjectValue) {
            writeValue((NewObjectValue) aValue);
            return;
        }
        if (aValue instanceof GetStaticValue) {
            writeValue((GetStaticValue) aValue);
            return;
        }
        if (aValue instanceof LongValue) {
            writeValue((LongValue) aValue);
            return;
        }
        if (aValue instanceof FixedBinaryValue) {
            writeValue((FixedBinaryValue) aValue);
            return;
        }
        if (aValue instanceof ComputedMemoryLocationReadValue) {
            writeValue((ComputedMemoryLocationReadValue) aValue);
            return;
        }
        if (aValue instanceof ComputedMemoryLocationWriteValue) {
            writeValue((ComputedMemoryLocationWriteValue) aValue);
            return;
        }
        if (aValue instanceof TypeConversionValue) {
            writeValue((TypeConversionValue) aValue);
            return;
        }
        if (aValue instanceof NullValue) {
            writeValue((NullValue) aValue);
            return;
        }
        if (aValue instanceof StackStartValue) {
            writeValue((StackStartValue) aValue);
            return;
        }
        if (aValue instanceof StackTopValue) {
            writeValue((StackTopValue) aValue);
            return;
        }
        if (aValue instanceof ShortValue) {
            writeValue((ShortValue) aValue);
            return;
        }
        if (aValue instanceof FloatValue) {
            writeValue((FloatValue) aValue);
            return;
        }
        if (aValue instanceof InvokeVirtualMethodValue) {
            writeValue((InvokeVirtualMethodValue) aValue);
            return;
        }
        throw new IllegalStateException("Not supported : " + aValue);
    }

    private void writeValue(InvokeVirtualMethodValue aValue) {
        print("(call_indirect $t_");
        print(WASMWriterUtils.toMethodSignature(aValue.getSignature()));
        println();

        WASMSSAWriter theChild = withDeeperIndent();
        theChild.println("(call_indirect $RESOLVEMETHOD");

        WASMSSAWriter theChild2 = theChild.withDeeperIndent();
        theChild2.print("(i32.load offset=4 ");
        theChild2.printVariableNameOrValue(aValue.getTarget());
        theChild2.println(")");   // This is id of the virtual table method

        theChild2.print("(i32.const ");

        BytecodeVirtualMethodIdentifier theMethodIdentifier = linkerContext.getMethodCollection().identifierFor(aValue.getMethodName(), aValue.getSignature());
        theChild2.print(theMethodIdentifier.getIdentifier());
        theChild2.println(")");

        theChild.println(")");

        theChild.printVariableNameOrValue(aValue.getTarget());

        for (Variable theVariable : aValue.getArguments()) {
            theChild.print(" ");
            theChild.printVariableNameOrValue(theVariable);
        }

        theChild.println(")");

        println(")");

    }

    private void writeValue(FloatValue aValue) {
        print("(f32.const ");
        print(aValue.getFloatValue());
        print(")");
    }

    private void writeValue(ShortValue aValue) {
        print("(i32.const ");
        print(aValue.getShortValue());
        print(")");
    }

    private void writeValue(StackStartValue aValue) {
        print("(get_global $STACK)");
    }

    private void writeValue(StackTopValue aValue) {
        print("(get_global $STACKTOP)");
    }

    private void writeValue(NullValue aValue) {
        print("(i32.const 0)");
    }

    private void writeValue(TypeConversionValue aValue) {
        printVariableNameOrValue(aValue.getVariable());
    }

    private void writeValue(ComputedMemoryLocationWriteValue aValue) {
        println("(i32.add ");

        withDeeperIndent().printVariableNameOrValue(aValue.getOrigin());
        println();
        withDeeperIndent().printVariableNameOrValue(aValue.getOffset());
        println();

        println(")");
    }

    private void writeValue(ComputedMemoryLocationReadValue aValue) {
        println("(i32.load (i32.add ");

        withDeeperIndent().printVariableNameOrValue(aValue.getOrigin());
        println();
        withDeeperIndent().printVariableNameOrValue(aValue.getOffset());
        println();

        println("))");
    }

    private void writeValue(FixedBinaryValue aValue) {
        switch (aValue.getOperator()) {
            case ISNULL: {
                print("(i32.eq ");
                printVariableNameOrValue(aValue.getValue1());
                print(" (i32.const 0))");
                break;
            }
            case ISNONNULL: {
                print("(i32.ne ");
                printVariableNameOrValue(aValue.getValue1());
                print(" (i32.const 0))");
                break;
            }
            case ISZERO: {
                print("(i32.eq ");
                printVariableNameOrValue(aValue.getValue1());
                print(" (i32.const 0))");
                break;
            }
            default: {
                throw new IllegalStateException("Not supported");
            }
        }
    }

    private void writeValue(LongValue aValue) {
        print("(i32.const ");
        print(aValue.getLongValue());
        print(")");
    }

    private void writeValue(GetStaticValue aValue) {
        BytecodeLinkedClass theLinkedClass = linkerContext.linkClass(BytecodeObjectTypeRef.fromUtf8Constant(aValue.getField().getClassIndex().getClassConstant().getConstant()));
        int theMemoryOffset = WASMWriterUtils.computeStaticFieldOffsetOf(aValue.getField().getNameAndTypeIndex().getNameAndType().getNameIndex().getName().stringValue(),
                theLinkedClass);

        String theClassName = WASMWriterUtils.toClassName(aValue.getField().getClassIndex().getClassConstant());
        print("(i32.load offset=");
        print(theMemoryOffset);
        println();

        WASMSSAWriter theChild = withDeeperIndent();
        theChild.print("(get_global $");
        theChild.print(theClassName);
        theChild.println("__staticdata)");

        println(")");
    }

    private void writeValue(NewObjectValue aValue) {

        BytecodeObjectTypeRef theType = BytecodeObjectTypeRef.fromUtf8Constant(aValue.getType().getConstant());

        String theMallocName = WASMWriterUtils.toMethodName(
                BytecodeObjectTypeRef.fromRuntimeClass(MemoryManager.class),
                "newObject",
                new BytecodeMethodSignature(BytecodeObjectTypeRef.fromRuntimeClass(
                        Address.class), new BytecodeTypeRef[] {BytecodePrimitiveTypeRef.INT, BytecodePrimitiveTypeRef.INT, BytecodePrimitiveTypeRef.INT}));

        BytecodeLinkedClass theLinkedClass = linkerContext.linkClass(theType);
        print("(call $");
        print(theMallocName);
        print(" (i32.const ");
        print(WASMWriterUtils.computeObjectSizeFor(theLinkedClass));
        print(") (i32.const ");
        print(theLinkedClass.getUniqueId());
        print(") (i32.const ");
        print(idResolver.resolveVTableMethodByType(theType));
        print(")) ;; object of type " + aValue.getType().getConstant().stringValue());
    }

    private void writeValue(GetFieldValue aValue) {
        print("(i32.const 0) ;; Field ref");
    }

    private void writeValue(DirectInvokeMethodValue aValue) {
        print("(call $");
        print(WASMWriterUtils.toMethodName(aValue.getClazz(), aValue.getMethodName(), aValue.getMethodSignature()));

        print(" ");
        printVariableNameOrValue(aValue.getTarget());

        for (Variable theVariable : aValue.getArguments()) {
            print(" ");
            printVariableNameOrValue(theVariable);
        }

        print(")");
    }

    private void writeValue(InvokeStaticMethodValue aValue) {
        print("(call $");
        print(WASMWriterUtils.toMethodName(aValue.getClassName(), aValue.getMethodName(), aValue.getSignature()));

        for (Variable theVariable : aValue.getArguments()) {
            print(" ");
            printVariableNameOrValue(theVariable);
        }

        print(")");
    }

    private void writeValue(VariableReferenceValue aValue) {
        printVariableNameOrValue(aValue.getVariable());
    }

    private void writeValue(ByteValue aValue) {
        print("(i32.const ");
        print(aValue.getByteValue());
        print(")");
    }

    private void writeValue(IntegerValue aValue) {
        print("(i32.const ");
        print(aValue.getIntValue());
        print(")");
    }

    private void writeValue(BinaryValue aValue) {
        switch (aValue.getOperator()) {
            case NOTEQUALS: {
                println("(i32.ne ");

                WASMSSAWriter theChild = withDeeperIndent();
                theChild.printVariableNameOrValue(aValue.getValue1());
                theChild.println();
                theChild.printVariableNameOrValue(aValue.getValue2());
                theChild.println();

                println(")");
                break;
            }
            case EQUALS: {
                println("(i32.eq ");

                WASMSSAWriter theChild = withDeeperIndent();
                theChild.printVariableNameOrValue(aValue.getValue1());
                theChild.println();
                theChild.printVariableNameOrValue(aValue.getValue2());
                theChild.println();

                println(")");
                break;
            }
            case LESSTHAN: {
                println("(i32.lt_s ");

                WASMSSAWriter theChild = withDeeperIndent();
                theChild.printVariableNameOrValue(aValue.getValue1());
                theChild.println();
                theChild.printVariableNameOrValue(aValue.getValue2());
                theChild.println();

                println(")");
                break;
            }
            case LESSTHANOREQUALS: {
                println("(i32.le_s ");

                WASMSSAWriter theChild = withDeeperIndent();
                theChild.printVariableNameOrValue(aValue.getValue1());
                theChild.println();
                theChild.printVariableNameOrValue(aValue.getValue2());
                theChild.println();

                println(")");
                break;
            }
            case GREATEROREQUALS: {
                println("(i32.gt_s ");

                WASMSSAWriter theChild = withDeeperIndent();
                theChild.printVariableNameOrValue(aValue.getValue1());
                theChild.println();
                theChild.printVariableNameOrValue(aValue.getValue2());
                theChild.println();

                println(")");
                break;
            }
            case ADD: {
                println("(i32.add ");

                WASMSSAWriter theChild = withDeeperIndent();
                theChild.printVariableNameOrValue(aValue.getValue1());
                theChild.println();
                theChild.printVariableNameOrValue(aValue.getValue2());
                theChild.println();

                println(")");
                break;
            }
            case SUB: {
                println("(i32.sub ");

                WASMSSAWriter theChild = withDeeperIndent();
                theChild.printVariableNameOrValue(aValue.getValue1());
                theChild.println();
                theChild.printVariableNameOrValue(aValue.getValue2());
                theChild.println();

                println(")");
                break;
            }
            default:
                throw new IllegalStateException("Operator not supported : " + aValue.getOperator());
        }
    }

    private void writeExpression(CommentExpression aExpression) {
        print(";; ");
        println(aExpression.getValue());
    }

    private void writeExpression(ReturnExpression aExpression) {
        printStackExit();
        println("(return)");
    }

    private void writeExpression(ReturnVariableExpression aExpression) {
        printStackExit();
        print("(return ");

        printVariableNameOrValue(aExpression.getVariable());

        println(")");
    }

    private void printVariableNameOrValue(Variable aVariable) {
        if (aVariable.getValue() instanceof PrimitiveValue) {
            writeValue(aVariable.getValue());
        } else {
            if (isStackVariable(aVariable)) {
                print("(i32.load (i32.sub (get_global $STACK) (i32.const ");
                print(stackOffsetFor(aVariable));
                print(")))");
            } else {
                print("(get_local ");
                print("$");
                print(aVariable.getName());
                print(")");
            }
        }
    }

    private void printStackEnter() {

        int theStackSize = stackSize();
        if (theStackSize > 0) {
            print("(set_global $STACKTOP (i32.add (get_global $STACKTOP) (i32.const ");
            print(theStackSize);
            println(")))");
        }

    }

    private void printStackExit() {

        int theStackSize = stackSize();
        if (theStackSize > 0) {
            print("(set_global $STACKTOP (i32.sub (get_global $STACKTOP) (i32.const ");
            print(theStackSize);
            println(")))");
        }
    }
}