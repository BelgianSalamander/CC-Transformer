package me.salamander.cctransformer.transformer;

import me.salamander.cctransformer.bytecodegen.BytecodeFactory;
import me.salamander.cctransformer.bytecodegen.ConstantFactory;
import me.salamander.cctransformer.transformer.analysis.*;
import me.salamander.cctransformer.transformer.config.*;
import me.salamander.cctransformer.util.ASMUtil;
import me.salamander.cctransformer.util.AncestorHashMap;
import me.salamander.cctransformer.util.FieldID;
import me.salamander.cctransformer.util.MethodID;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

public class TypeTransformer {
    private static final Path OUT_DIR = Path.of("run", "transformed");
    public static final String MIX = "$$cc_transformed";
    public static final int MAGIC = 0xDEADBEEF;
    private static final Set<String> warnings = new HashSet<>();

    private final Config config;
    private final ClassNode classNode;
    private final ClassNode newClassNode;
    private final Map<MethodID, AnalysisResults> analysisResults = new HashMap<>();
    private final Map<MethodID, List<FutureMethodBinding>> futureMethodBindings = new HashMap<>();
    private final AncestorHashMap<FieldID, TransformTrackingValue> fieldPseudoValues;
    private final ClassTransformInfo transformInfo;
    private final FieldID isTransformedField;
    private final boolean addSafety;
    private final Set<MethodNode> lambdaTransformers = new HashSet<>();
    private final Set<MethodNode> newMethods = new HashSet<>();

    private final String renameTo;

    public TypeTransformer(Config config, ClassNode classNode, boolean duplicateClass, boolean addSafety) {
        this.config = config;
        this.classNode = classNode;
        this.fieldPseudoValues = new AncestorHashMap<>(config.getHierarchy());
        this.addSafety = addSafety;

        //Create field pseudo values
        for(var field: classNode.fields){
            TransformTrackingValue value = new TransformTrackingValue(Type.getType(field.desc), fieldPseudoValues);
            fieldPseudoValues.put(new FieldID(Type.getObjectType(classNode.name), field.name, Type.getType(field.desc)), value);
        }

        this.transformInfo = config.getClasses().get(Type.getObjectType(classNode.name));

        if(duplicateClass){
            this.newClassNode = new ClassNode();

            classNode.accept(newClassNode);

            System.out.println(newClassNode.methods.size());

            MethodNode methodNode = newClassNode.methods.stream().filter(m -> m.name.equals("<clinit>") && m.desc.equals("()V")).findAny().orElse(null);
            if(methodNode == null){
                methodNode = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                newClassNode.methods.add(methodNode);
            }
            methodNode.instructions.clear();
            methodNode.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
            methodNode.visitLdcInsn(newClassNode.name + " was loaded!");
            methodNode.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
            methodNode.visitInsn(Opcodes.RETURN);
            renameTo = newClassNode.name + "_transformed";
            isTransformedField = null;
        }else{
            this.newClassNode = null;
            renameTo = null;

            isTransformedField = new FieldID(Type.getObjectType(classNode.name), "isTransformed" + MIX, Type.BOOLEAN_TYPE);
            classNode.fields.add(isTransformedField.toNode(false, Opcodes.ACC_FINAL));

            for(MethodNode methodNode: classNode.methods){
                if(methodNode.name.equals("<init>")){
                    insertAtReturn(methodNode, () -> {
                       InsnList instructions = new InsnList();
                       instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                       instructions.add(new InsnNode(Opcodes.ICONST_0));
                       instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, classNode.name, isTransformedField.name(), "Z"));
                       return instructions;
                    });
                }
            }
        }
    }

    public void cleanUpTransform(){
        if(newClassNode != null){
            ASMUtil.rename(newClassNode, newClassNode.name + "_transformed");
        }

        if(newClassNode != null){
            //There is no support for inner classes so we remove them
            newClassNode.innerClasses.removeIf(
                    c -> c.name.contains(classNode.name)
            );

            newClassNode.nestMembers.removeIf(
                    c -> c.contains(classNode.name)
            );
        }else{
            for(MethodNode transformer: lambdaTransformers){
                classNode.methods.add(transformer);
            }

            for(MethodNode newMethod: newMethods){
                classNode.methods.add(newMethod);
            }
        }

        if(newClassNode == null){
            makeFieldCasts();
        }
    }

    public void transformMethod(MethodNode methodNode) {
        long start = System.currentTimeMillis();
        MethodID methodID = new MethodID(classNode.name, methodNode.name, methodNode.desc, MethodID.CallType.VIRTUAL); //Call subType doesn't matter much
        AnalysisResults results = analysisResults.get(methodID);

        MethodNode newMethod;

        if(newClassNode != null) {
            newMethod = newClassNode.methods.stream().filter(m -> m.name.equals(methodNode.name) && m.desc.equals(methodNode.desc)).findFirst().orElse(null);
        }else{
            newMethod = ASMUtil.copy(methodNode);
            newMethods.add(newMethod);
            markSynthetic(newMethod, "AUTO-TRANSFORMED", methodNode.name + methodNode.desc);
        }

        if(newMethod == null){
            throw new RuntimeException("Method " + methodID + " not found in new class");
        }

        if((methodNode.access & Opcodes.ACC_ABSTRACT) != 0){
            //We just need to change the descriptor
            TransformSubtype[] actualParameters;
            if((methodNode.access & Opcodes.ACC_STATIC) == 0){
                actualParameters =  new TransformSubtype[results.argTypes().length - 1];
                System.arraycopy(results.argTypes(), 1, actualParameters, 0, actualParameters.length);
            }else{
                actualParameters = results.argTypes();
            }

            //Change descriptor
            newMethod.desc = MethodParameterInfo.getNewDesc(TransformSubtype.of(null), actualParameters, methodNode.desc);
            System.out.println("Transformed method '" + methodID + "' in " + (System.currentTimeMillis() - start) + "ms");

            //TODO: (Optional) Variable name tables

            return;
        }

        if(results == null){
            throw new RuntimeException("Method " + methodID + " not analyzed");
        }

        AbstractInsnNode[] insns = newMethod.instructions.toArray();
        boolean[] expandedEmitter = new boolean[insns.length];
        boolean[] expandedConsumer = new boolean[insns.length];
        int[][] vars = new int[insns.length][methodNode.maxLocals];
        TransformSubtype[][] varTypes = new TransformSubtype[insns.length][methodNode.maxLocals];

        int maxLocals = 0;

        //Generate var table
        //Note: This variable table might not work with obfuscated bytecode. It relies on variables being added and removed in a stack-like fashion
        for(int i = 0; i < insns.length; i++){
            Frame<TransformTrackingValue> frame = results.frames()[i];
            if(frame == null) continue;
            int newIndex = 0;
            for(int j = 0; j < methodNode.maxLocals;){
                vars[i][j] = newIndex;
                varTypes[i][j] = frame.getLocal(j).getTransform();
                newIndex += frame.getLocal(j).getTransformedSize();

                j += frame.getLocal(j).getSize();
            }
            maxLocals = Math.max(maxLocals, newIndex);
        }

        VariableManager varCreator = new VariableManager(maxLocals, insns.length);

        //Analysis results come from the original method, and we need to transform the new method, so we need to be able to get the new instructions that correspond to the old ones
        Map<AbstractInsnNode, Integer> indexLookup = new HashMap<>();

        //For simplicity, we add all teh instructions from both methods
        AbstractInsnNode[] oldInsns = methodNode.instructions.toArray();

        for(int i = 0; i < oldInsns.length; i++){
            indexLookup.put(insns[i], i);
            indexLookup.put(oldInsns[i], i);
        }

        BytecodeFactory[][] syntheticEmitters = new BytecodeFactory[insns.length][];

        AbstractInsnNode[] instructions = newMethod.instructions.toArray();
        Frame<TransformTrackingValue>[] frames = results.frames();
        MethodParameterInfo[] methodInfos = new MethodParameterInfo[insns.length];
        for(int i = 0; i < insns.length; i++){
            AbstractInsnNode insn = instructions[i];
            Frame<TransformTrackingValue> frame = frames[i];
            if(insn instanceof MethodInsnNode methodCall){
                MethodID calledMethod = MethodID.from(methodCall);

                TransformTrackingValue returnValue = null;
                if (calledMethod.getDescriptor().getReturnType() != Type.VOID_TYPE) {
                    returnValue = ASMUtil.getTop(frames[i + 1]);
                }

                int argCount = ASMUtil.argumentCount(calledMethod.getDescriptor().getDescriptor(), calledMethod.isStatic());
                TransformTrackingValue[] args = new TransformTrackingValue[argCount];
                for (int j = 0; j < args.length; j++) {
                    args[j] = frame.getStack(frame.getStackSize() - argCount + j);
                }

                List<MethodParameterInfo> infos = config.getMethodParameterInfo().get(calledMethod);

                if(infos != null) {
                    for (MethodParameterInfo info : infos) {
                        if (info.getTransformCondition().checkValidity(returnValue, args) == 1) {
                            methodInfos[i] = info;
                            break;
                        }
                    }
                }
            }
        }

        TransformContext context = new TransformContext(newMethod, results, instructions, expandedEmitter, expandedConsumer, new boolean[insns.length], syntheticEmitters, vars, varTypes, varCreator, indexLookup, methodInfos);

        detectAllRemovedEmitters(newMethod, context);

        createEmitters(context);

        transformMethod(methodNode, newMethod, context);

        System.out.println("Transformed method '" + methodID + "' in " + (System.currentTimeMillis() - start) + "ms");
    }

    private void createEmitters(TransformContext context) {
        for (int i = 0; i < context.instructions.length; i++) {
            if(context.removedEmitter()[i]){
                if(context.syntheticEmitters()[i] == null){
                    //Generate synthetic emitter
                    //For that we need the value
                    TransformTrackingValue value = ASMUtil.getTop(context.analysisResults().frames()[i + 1]);
                    generateEmitter(context, value);
                }
            }
        }
    }

    private void detectAllRemovedEmitters(MethodNode newMethod, TransformContext context) {
        boolean[] prev;
        Frame<TransformTrackingValue>[] frames = context.analysisResults().frames();

        do{
            //Keep detecting new ones until we don't find any more
            prev = Arrays.copyOf(context.removedEmitter(), context.removedEmitter().length);

            for(int i = 0; i < context.removedEmitter().length; i++){
                AbstractInsnNode instruction = context.instructions()[i];
                Frame<TransformTrackingValue> frame = frames[i];

                int consumed = ASMUtil.stackConsumed(instruction);
                int opcode = instruction.getOpcode();

                if(instruction instanceof MethodInsnNode methodCall){
                    MethodParameterInfo info = context.methodInfos()[i];
                    if(info != null){
                        if(info.getReplacement().changeParameters()){
                            for (int j = 0; j < consumed; j++) {
                                TransformTrackingValue arg = frame.getStack(frame.getStackSize() - consumed + j);
                                markRemoved(arg, context);
                            }
                        }
                    }
                }else if(opcode == Opcodes.LCMP || opcode == Opcodes.FCMPL || opcode == Opcodes.FCMPG || opcode == Opcodes.DCMPL || opcode == Opcodes.DCMPG || opcode == Opcodes.IF_ICMPEQ || opcode == Opcodes.IF_ICMPNE || opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE){
                    //Get two values
                    TransformTrackingValue left = frame.getStack(frame.getStackSize() - 2);
                    TransformTrackingValue right = frame.getStack(frame.getStackSize() - 1);

                    //We can assume the two transforms are the same. This check is just to make sure there isn't a bug in the analyzer
                    if(!left.getTransform().equals(right.getTransform())){
                        throw new RuntimeException("The two transforms should be the same");
                    }

                    //If the transform has more than one subType we will need to separate them so we must remove the emitter
                    if(left.getTransform().transformedTypes(left.getType()).size() > 1){
                        markRemoved(left, context);
                        markRemoved(right, context);
                    }
                }

                boolean remove = false;
                for(int j = 0; j < consumed; j++){
                    TransformTrackingValue arg = frame.getStack(frame.getStackSize() - consumed + j);
                    if(isRemoved(arg, context)){
                        remove = true;
                    }
                }

                if(remove){
                    for(int j = 0; j < consumed; j++){
                        TransformTrackingValue arg = frame.getStack(frame.getStackSize() - consumed + j);
                        markRemoved(arg, context);
                    }
                }
            }
        }while(!Arrays.equals(prev, context.removedEmitter()));
    }

    private boolean isRemoved(TransformTrackingValue value, TransformContext context){
        boolean isAllRemoved = true;
        boolean isAllPresent = true;

        for(AbstractInsnNode source: value.getSource()){
            int sourceIndex = context.indexLookup().get(source);
            if(context.removedEmitter()[sourceIndex]){
                isAllPresent = false;
            }else{
                isAllRemoved = false;
            }
        }

        if(!(isAllPresent || isAllRemoved)){
            throw new IllegalStateException("Value is neither all present nor all removed");
        }

        return isAllRemoved;
    }

    private void markRemoved(TransformTrackingValue value, TransformContext context){
        for(AbstractInsnNode source: value.getSource()){
            int sourceIndex = context.indexLookup().get(source);
            context.removedEmitter()[sourceIndex] = true;
        }
    }

    private void transformMethod(MethodNode oldMethod, MethodNode methodNode, TransformContext context) {
        //Step One: change descriptor
        TransformSubtype[] actualParameters;
        if((methodNode.access & Opcodes.ACC_STATIC) == 0){
            actualParameters =  new TransformSubtype[context.analysisResults().argTypes().length - 1];
            System.arraycopy(context.analysisResults().argTypes(), 1, actualParameters, 0, actualParameters.length);
        }else{
            actualParameters = context.analysisResults().argTypes();
        }

        //Change descriptor
        String newDescriptor = MethodParameterInfo.getNewDesc(TransformSubtype.of(null), actualParameters, methodNode.desc);
        methodNode.desc = newDescriptor;

        boolean renamed = false;

        if(newDescriptor.equals(oldMethod.desc) && newClassNode == null){
            methodNode.name += MIX;
            renamed = true;
        }

        modifyVariableTable(methodNode, context);

        modifyCode(methodNode, context);

        if(renamed){
            //TODO: Check if dispatch is actually necessary. This could be done by checking if the method accesses any transformed fields

            InsnList dispatch = new InsnList();
            LabelNode label = new LabelNode();
            dispatch.add(new VarInsnNode(Opcodes.ALOAD, 0));
            dispatch.add(new FieldInsnNode(Opcodes.GETFIELD, isTransformedField.owner().getInternalName(), isTransformedField.name(), isTransformedField.desc().getDescriptor()));
            dispatch.add(new JumpInsnNode(Opcodes.IFEQ, label));
            //Dispatch to 3int
            dispatch.add(new VarInsnNode(Opcodes.ALOAD, 0));
            int index = 1;
            for(Type arg: Type.getArgumentTypes(newDescriptor)){
                dispatch.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), index));
                index += arg.getSize();
            }
            dispatch.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, isTransformedField.owner().getInternalName(), methodNode.name, methodNode.desc, false));
            dispatch.add(new InsnNode(Type.getReturnType(methodNode.desc).getOpcode(Opcodes.IRETURN)));
            dispatch.add(label);

            oldMethod.instructions.insertBefore(oldMethod.instructions.getFirst(), dispatch);
        }else if(addSafety && (methodNode.access & Opcodes.ACC_SYNTHETIC) == 0){
            InsnList dispatch = new InsnList();
            LabelNode label = new LabelNode();
            dispatch.add(new VarInsnNode(Opcodes.ALOAD, 0));
            dispatch.add(new FieldInsnNode(Opcodes.GETFIELD, isTransformedField.owner().getInternalName(), isTransformedField.name(), isTransformedField.desc().getDescriptor()));
            dispatch.add(new JumpInsnNode(Opcodes.IFEQ, label));

            dispatch.add(new LdcInsnNode(classNode.name));
            dispatch.add(new LdcInsnNode(oldMethod.name));
            dispatch.add(new LdcInsnNode(oldMethod.desc));
            dispatch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "me/salamander/cctransformer/transformer/TypeTransformer", "emitWarning", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", false));

            dispatch.add(new VarInsnNode(Opcodes.ALOAD, 0));
            int index = 1;
            for(Type arg: Type.getArgumentTypes(oldMethod.desc)){
                TransformSubtype argType = context.varTypes[0][index];
                int finalIndex = index;
                dispatch.add(argType.convertToTransformed(() -> {
                    InsnList load = new InsnList();
                    load.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), finalIndex));
                    return load;
                }, lambdaTransformers, classNode.name));
                index += arg.getSize();
            }

            dispatch.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, classNode.name, methodNode.name, methodNode.desc, false));
            dispatch.add(new InsnNode(Type.getReturnType(methodNode.desc).getOpcode(Opcodes.IRETURN)));

            dispatch.add(label);

            oldMethod.instructions.insertBefore(oldMethod.instructions.getFirst(), dispatch);
        }
    }

    private void modifyCode(MethodNode methodNode, TransformContext context) {
        AbstractInsnNode[] instructions = context.instructions();
        Frame<TransformTrackingValue>[] frames = context.analysisResults().frames();

        for (int i = 0; i < instructions.length; i++) {
            if(context.removedEmitter()[i]){
                continue;
            }

            AbstractInsnNode instruction = instructions[i];
            Frame<TransformTrackingValue> frame = frames[i];

            boolean ensureValuesAreOnStack = true;

            int opcode = instruction.getOpcode();

            if(instruction instanceof MethodInsnNode methodCall){
                MethodID methodID = MethodID.from(methodCall);

                TransformTrackingValue returnValue = null;
                if (methodID.getDescriptor().getReturnType() != Type.VOID_TYPE) {
                    returnValue = ASMUtil.getTop(frames[i + 1]);
                }

                int argCount = ASMUtil.argumentCount(methodID.getDescriptor().getDescriptor(), methodID.isStatic());
                TransformTrackingValue[] args = new TransformTrackingValue[argCount];
                for (int j = 0; j < args.length; j++) {
                    args[j] = frame.getStack(frame.getStackSize() - argCount + j);
                }

                MethodParameterInfo info = context.methodInfos[i];
                if(info != null) {

                    if (info.getTransformCondition().checkValidity(returnValue, args) == 1) {
                        applyReplacement(context, instructions, frames, i, methodCall, info, args);
                    }

                    if(info.getReplacement().changeParameters()){
                        ensureValuesAreOnStack = false;
                    }
                }else{
                    //Default replacement
                    if(returnValue != null && returnValue.getTransform().transformedTypes(returnValue.getType()).size() > 1){
                        throw new IllegalStateException("Cannot generate default replacement for method with multiple return types '" + methodID + "'");
                    }

                    applyDefaultReplacement(context, instructions, frames, i, methodCall, returnValue, args);
                }
            }else if(instruction instanceof VarInsnNode varNode){
                int originalVarIndex = varNode.var;
                int newVarIndex = context.varLookup()[i][originalVarIndex];

                int baseOpcode = switch (varNode.getOpcode()){
                    case Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.LLOAD -> Opcodes.ILOAD;
                    case Opcodes.ASTORE, Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.LSTORE -> Opcodes.ISTORE;
                    default -> throw new IllegalStateException("Unknown opcode: " + varNode.getOpcode());
                };

                TransformSubtype varType = context.varTypes()[i + (baseOpcode == Opcodes.ISTORE ? 1 : 0)][originalVarIndex];

                List<Type> types = varType.transformedTypes(ASMUtil.getType(varNode.getOpcode()));

                List<Integer> vars = new ArrayList<>();
                for (Type subType : types) {
                    vars.add(newVarIndex);
                    newVarIndex += subType.getSize();
                }

                //If the variable is being stored we must reverse the order of the types
                if(baseOpcode == Opcodes.ISTORE){
                    Collections.reverse(types);
                    Collections.reverse(vars);
                }

                varNode.var = vars.get(0);
                varNode.setOpcode(types.get(0).getOpcode(baseOpcode));

                InsnList extra = new InsnList();

                for(int j = 1; j < types.size(); j++){
                    extra.add(new VarInsnNode(types.get(j).getOpcode(baseOpcode), vars.get(j)));
                }

                context.target().instructions.insert(varNode, extra);
            }else if(instruction instanceof IincInsnNode iincNode){
                int originalVarIndex = iincNode.var;
                int newVarIndex = context.varLookup()[i][originalVarIndex];
                iincNode.var = newVarIndex;
            }else if(ASMUtil.isConstant(instruction)){
                //Check if value is expanded
                ensureValuesAreOnStack = false;
                TransformTrackingValue value = ASMUtil.getTop(frames[i + 1]);
                if(value.getTransformType() != null){
                    if(value.getTransform().getSubtype() != TransformSubtype.SubType.NONE){
                        throw new IllegalStateException("Cannot expand constant value of subType " + value.getTransform().getSubtype());
                    }

                    Object constant = ASMUtil.getConstant(instruction);

                    BytecodeFactory[] replacement = value.getTransformType().getConstantReplacements().get(constant);
                    if(replacement == null){
                        throw new IllegalStateException("Cannot expand constant value of subType " + value.getTransformType());
                    }

                    InsnList newInstructions = new InsnList();
                    for(BytecodeFactory factory : replacement){
                        newInstructions.add(factory.generate());
                    }

                    context.target().instructions.insert(instruction, newInstructions);
                    context.target().instructions.remove(instruction);
                }
            }else if(opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE || opcode == Opcodes.IF_ICMPEQ || opcode == Opcodes.IF_ICMPNE) {
                TransformTrackingValue left = frame.getStack(frame.getStackSize() - 2);
                TransformTrackingValue right = frame.getStack(frame.getStackSize() - 1);

                int baseOpcode = switch (opcode){
                    case Opcodes.IF_ACMPEQ, Opcodes.IF_ICMPEQ -> Opcodes.IF_ICMPEQ;
                    case Opcodes.IF_ACMPNE, Opcodes.IF_ICMPNE -> Opcodes.IF_ICMPNE;
                    default -> throw new IllegalStateException("Unknown opcode: " + opcode);
                };

                JumpInsnNode jump = context.getActual((JumpInsnNode) instruction);

                if(left.getTransformType() != null && right.getTransformType() != null){
                    ensureValuesAreOnStack = false;
                    List<Type> types = left.transformedTypes();

                    if(types.size() == 1){
                        jump.setOpcode(types.get(0).getOpcode(baseOpcode));
                    }else{
                        BytecodeFactory[] replacementLeft = context.syntheticEmitters()[context.indexLookup().get(left.getSource().iterator().next())];
                        BytecodeFactory[] replacementRight = context.syntheticEmitters()[context.indexLookup().get(right.getSource().iterator().next())];

                        LabelNode success = jump.label;
                        LabelNode failure = new LabelNode();

                        InsnList newCmp = new InsnList();

                        for(int j = 0; j < types.size(); j++){
                            Type subType = types.get(j);
                            newCmp.add(replacementLeft[j].generate());
                            newCmp.add(replacementRight[j].generate());

                            if(baseOpcode == Opcodes.IF_ICMPEQ){
                                if(j == types.size() - 1){
                                    newCmp.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, success));
                                }else {
                                    newCmp.add(new JumpInsnNode(subType.getOpcode(Opcodes.IF_ICMPNE), failure));
                                }
                            }else{
                                newCmp.add(new JumpInsnNode(subType.getOpcode(Opcodes.IF_ICMPNE), success));
                            }
                        }

                        newCmp.add(failure);

                        context.target().instructions.insertBefore(jump, newCmp);
                        context.target().instructions.remove(jump);
                    }
                }
            }else if(opcode == Opcodes.LCMP || opcode == Opcodes.FCMPL || opcode == Opcodes.FCMPG || opcode == Opcodes.DCMPL || opcode == Opcodes.DCMPG){
                TransformTrackingValue left = frame.getStack(frame.getStackSize() - 2);
                TransformTrackingValue right = frame.getStack(frame.getStackSize() - 1);

                TransformTrackingValue result = ASMUtil.getTop(frames[i + 1]);

                if(result.getConsumers().size() != 1){
                    throw new IllegalStateException("Expected one consumer, found " + result.getConsumers().size());
                }


                if(left.getTransformType() != null && right.getTransformType() != null){
                    ensureValuesAreOnStack = false;
                    JumpInsnNode jump = context.getActual((JumpInsnNode) result.getConsumers().iterator().next());

                    int baseOpcode = switch (jump.getOpcode()){
                        case Opcodes.IFEQ -> Opcodes.IF_ICMPEQ;
                        case Opcodes.IFNE -> Opcodes.IF_ICMPNE;
                        default -> throw new IllegalStateException("Unknown opcode: " + jump.getOpcode());
                    };

                    List<Type> types = left.transformedTypes();

                    if(types.size() == 1){
                        Type subType = types.get(0);
                        if(subType.getSort() == Type.INT || subType.getSort() == Type.OBJECT){
                            jump.setOpcode(subType.getOpcode(baseOpcode));
                            context.target().instructions.remove(instruction);
                        }else{
                            AbstractInsnNode newOp = new InsnNode(subType.getOpcode(baseOpcode));
                            context.target().instructions.insertBefore(instruction, newOp);
                            context.target().instructions.remove(instruction);
                        }
                    }else{
                        BytecodeFactory[] replacementLeft = context.syntheticEmitters()[context.indexLookup().get(left.getSource().iterator().next())];
                        BytecodeFactory[] replacementRight = context.syntheticEmitters()[context.indexLookup().get(right.getSource().iterator().next())];

                        LabelNode success = jump.label;
                        LabelNode failure = new LabelNode();

                        InsnList newCmp = new InsnList();
                        for(int j = 0; j < types.size(); j++){
                            Type subType = types.get(j);
                            newCmp.add(replacementLeft[j].generate());
                            newCmp.add(replacementRight[j].generate());

                            if(subType.getSort() == Type.INT || subType.getSort() == Type.OBJECT){
                                if(baseOpcode == Opcodes.IF_ICMPEQ){
                                    if(j == types.size() - 1) {
                                        newCmp.add(new JumpInsnNode(subType.getOpcode(Opcodes.IF_ICMPEQ), success));
                                    }else{
                                        newCmp.add(new JumpInsnNode(subType.getOpcode(Opcodes.IF_ICMPNE), failure));
                                    }
                                }else{
                                    newCmp.add(new JumpInsnNode(subType.getOpcode(Opcodes.IF_ICMPNE), success));
                                }
                            }else{
                                newCmp.add(new InsnNode(ASMUtil.getCompare(subType)));
                                if(baseOpcode == Opcodes.IF_ICMPEQ){
                                    if(j == types.size() - 1) {
                                        newCmp.add(new JumpInsnNode(Opcodes.IFEQ, success));
                                    }else{
                                        newCmp.add(new JumpInsnNode(Opcodes.IFNE, failure));
                                    }
                                }else{
                                    newCmp.add(new JumpInsnNode(Opcodes.IFNE, success));
                                }
                            }
                        }

                        newCmp.add(failure);

                        context.target().instructions.insertBefore(instruction, newCmp);
                        context.target().instructions.remove(instruction);
                        context.target().instructions.remove(jump);
                    }
                }
            }else if(instruction instanceof InvokeDynamicInsnNode dynamicInsnNode){
                Handle methodReference = (Handle) dynamicInsnNode.bsmArgs[1];
                boolean isStatic = methodReference.getTag() == Opcodes.H_INVOKESTATIC;
                int staticOffset = isStatic ? 0 : 1;

                Type[] args = Type.getArgumentTypes(dynamicInsnNode.desc);
                TransformTrackingValue[] values = new TransformTrackingValue[args.length];
                for(int j = 0; j < values.length; j++){
                    values[j] = frame.getStack(frame.getStackSize() - args.length + j);
                }

                TransformTrackingValue returnValue = ASMUtil.getTop(frames[i + 1]);

                dynamicInsnNode.desc = MethodParameterInfo.getNewDesc(returnValue, values, dynamicInsnNode.desc);

                Type referenceDesc = (Type) dynamicInsnNode.bsmArgs[0];
                assert referenceDesc.equals(dynamicInsnNode.bsmArgs[2]);

                String methodName = methodReference.getName();
                String methodDesc = methodReference.getDesc();
                String methodOwner = methodReference.getOwner();
                if(!methodOwner.equals(getTransformed().name)){
                    throw new IllegalStateException("Method reference must be in the same class");
                }

                //For lookups we do need to use the old owner
                MethodID methodID = new MethodID(classNode.name, methodName, methodDesc, MethodID.CallType.VIRTUAL); // call subType doesn't matter
                AnalysisResults results = analysisResults.get(methodID);
                if(results == null){
                    throw new IllegalStateException("Method not analyzed '" + methodID + "'");
                }

                String newDesc = results.getNewDesc();
                Type[] newArgs = Type.getArgumentTypes(newDesc);
                Type[] referenceArgs = newArgs;

                Type[] lambdaArgs = new Type[newArgs.length - values.length + staticOffset];
                System.arraycopy(newArgs, values.length - staticOffset, lambdaArgs, 0, lambdaArgs.length);

                String newReferenceDesc = Type.getMethodType(Type.getReturnType(newDesc), referenceArgs).getDescriptor();
                String lambdaDesc = Type.getMethodType(Type.getReturnType(newDesc), lambdaArgs).getDescriptor();

                dynamicInsnNode.bsmArgs[0] = Type.getMethodType(lambdaDesc);
                dynamicInsnNode.bsmArgs[1] = new Handle(methodReference.getTag(), methodReference.getOwner(), methodReference.getName(), newReferenceDesc, methodReference.isInterface());
                dynamicInsnNode.bsmArgs[2] = dynamicInsnNode.bsmArgs[0];
            }else if(opcode == Opcodes.NEW){
                TransformTrackingValue value = ASMUtil.getTop(frames[i + 1]);
                TypeInsnNode newInsn = (TypeInsnNode) instruction;
                newInsn.desc = value.getTransform().getSingleType().getInternalName();
            }

            if(ensureValuesAreOnStack){
                //We know that either all values are on the stack or none are so we just check the first
                int consumers = ASMUtil.stackConsumed(instruction);
                if(consumers > 0){
                    TransformTrackingValue value = ASMUtil.getTop(frame);
                    int producerIndex = context.indexLookup().get(value.getSource().iterator().next());
                    if(context.removedEmitter()[producerIndex]){
                        //None of the values are on the stack
                        InsnList load = new InsnList();
                        for (int j = 0; j < consumers; j++) {
                            TransformTrackingValue arg = frame.getStack(frame.getStackSize() - consumers + j);
                            BytecodeFactory[] emitters = context.syntheticEmitters()[context.indexLookup().get(arg.getSource().iterator().next())];
                            for (BytecodeFactory emitter : emitters) {
                                load.add(emitter.generate());
                            }
                        }
                        context.target().instructions.insertBefore(instruction, load);
                    }
                }
            }
        }
    }

    private void applyDefaultReplacement(TransformContext context, AbstractInsnNode[] instructions, Frame<TransformTrackingValue>[] frames, int i, MethodInsnNode methodCall, TransformTrackingValue returnValue, TransformTrackingValue[] args) {
        //Step 1: Create new descriptor
        boolean isStatic = (methodCall.getOpcode() == Opcodes.INVOKESTATIC);
        int staticOffset = isStatic ? 0 : 1;
        TransformSubtype returnType = TransformSubtype.of(null);
        TransformSubtype[] argTypes = new TransformSubtype[args.length - staticOffset];

        if(returnValue != null){
            returnType = returnValue.getTransform();
        }

        for (int j = staticOffset; j < args.length; j++) {
            argTypes[j - staticOffset] = args[j].getTransform();
        }

        String newDescriptor = MethodParameterInfo.getNewDesc(returnType, argTypes, methodCall.desc);

        methodCall.desc = newDescriptor;

        if(!isStatic){
            List<Type> types = args[0].transformedTypes();
            if(types.size() != 1){
                throw new IllegalStateException("Expected 1 subType but got " + types.size());
            }
            methodCall.owner = types.get(0).getInternalName();
        }
    }

    private void applyReplacement(TransformContext context, AbstractInsnNode[] instructions, Frame<TransformTrackingValue>[] frames, int i, MethodInsnNode methodCall, MethodParameterInfo info, TransformTrackingValue[] args) {
        //Step 1: Check that all the values will be on the stack
        boolean allValuesOnStack = true;

        for(TransformTrackingValue value: args){
            for(AbstractInsnNode source: value.getSource()){
                int index = context.indexLookup().get(source);
                if(context.removedEmitter()[index]){
                    allValuesOnStack = false;
                    break;
                }
            }
            if(!allValuesOnStack){
                break;
            }
        }

        MethodReplacement replacement = info.getReplacement();
        if(replacement.changeParameters()){
            allValuesOnStack = false;
        }

        Type returnType = Type.getReturnType(methodCall.desc);
        if(!replacement.changeParameters() && info.getReturnType().transformedTypes(returnType).size() > 1){
            throw new IllegalStateException("Multiple return types not supported");
        }

        if(allValuesOnStack){
            //Simply remove the method call and replace it
            context.target().instructions.insert(methodCall, replacement.getBytecodeFactories()[0].generate());
            context.target().instructions.remove(methodCall);
            context.expandedConsumer()[i] = true;
        }else{
            //Store all the parameters
            BytecodeFactory[][] paramGenerators = new BytecodeFactory[args.length][];
            for(int j = 0; j < args.length; j++){
                paramGenerators[j] = getEmitter(context, args[j]);
            }

            InsnList replacementInstructions = new InsnList();

            for (int j = 0; j < replacement.getBytecodeFactories().length; j++) {
                List<Integer>[] indices = replacement.getParameterIndexes()[j];
                for (int k = 0; k < indices.length; k++) {
                    for (int index : indices[k]) {
                        replacementInstructions.add(paramGenerators[k][index].generate());
                    }
                }
                replacementInstructions.add(replacement.getBytecodeFactories()[j].generate());
            }

            //Call finalizer
            if(replacement.getFinalizer() != null){
                List<Integer>[] indices = replacement.getFinalizerIndices();
                for(int j = 0; j < indices.length; j++){
                    for(int index: indices[j]){
                        replacementInstructions.add(paramGenerators[j][index].generate());
                    }
                }
                replacementInstructions.add(replacement.getFinalizer().generate());
            }

            //Step 2: Insert new code
            context.target().instructions.insert(methodCall, replacementInstructions);
            context.target().instructions.remove(methodCall);

            context.expandedConsumer()[i] = true;
            context.expandedEmitter()[i] = true;
        }
    }

    private BytecodeFactory[] getEmitter(TransformContext context, TransformTrackingValue arg) {
        return context.syntheticEmitters[context.indexLookup().get(arg.getSource().iterator().next())];
    }

    private BytecodeFactory[] generateEmitter(TransformContext context, TransformTrackingValue arg) {
        BytecodeFactory[] ret;

        if(arg.getSource().size() > 1){
            //Check that all the sources are either all removed or all not removed
            boolean allRemoved = true;
            boolean allNotRemoved = true;
            for(AbstractInsnNode source: arg.getSource()){
                int index = context.indexLookup().get(source);
                if(context.removedEmitter()[index]){
                    allNotRemoved = false;
                }else{
                    allRemoved = false;
                }
            }

            if(!(allRemoved || allNotRemoved)){
                throw new IllegalStateException("Source removed-ness mismatch");
            }

            ret = saveInVar(context, arg);
        }else{
            AbstractInsnNode source = arg.getSource().iterator().next();
            int index = context.indexLookup().get(source);

            AbstractInsnNode actualSource = context.instructions()[index];

            if(source instanceof VarInsnNode varLoad){
                List<Type> transformedTypes = arg.getTransform().transformedTypes(arg.getType());
                ret = new BytecodeFactory[transformedTypes.size()];
                int varIndex = context.varLookup()[index][varLoad.var];
                context.target.instructions.remove(actualSource);
                for(int i = 0; i < transformedTypes.size(); i++){
                    int finalI = i;
                    int finalVarIndex = varIndex;
                    ret[i] = () -> {
                        InsnList instructions = new InsnList();
                        instructions.add(new VarInsnNode(transformedTypes.get(finalI).getOpcode(Opcodes.ILOAD), finalVarIndex));
                        return instructions;
                    };
                    varIndex += transformedTypes.get(finalI).getSize();
                }
            }else if(ASMUtil.isConstant(actualSource)) {
                Object constant = ASMUtil.getConstant(actualSource);

                context.target().instructions.remove(actualSource);

                if(arg.getTransformType() != null & arg.getTransform().getSubtype() == TransformSubtype.SubType.NONE){
                    ret = arg.getTransformType().getConstantReplacements().get(constant);
                    if(ret == null){
                        throw new IllegalStateException("No constant replacement found for " + constant);
                    }
                }else{
                    ret = new BytecodeFactory[1];
                    ret[0] = new ConstantFactory(constant);
                }
            }else{
                ret = saveInVar(context, arg);
            }
        }

        for(AbstractInsnNode source: arg.getSource()){
            int index = context.indexLookup().get(source);
            context.syntheticEmitters()[index] = ret;
        }

        return ret;
    }

    private BytecodeFactory[] saveInVar(TransformContext context, TransformTrackingValue arg) {
        //Store in var
        //Step one: Find the range of instructions where it is needed
        int minIndex = Integer.MAX_VALUE;
        int maxIndex = Integer.MIN_VALUE;

        for(AbstractInsnNode source: arg.getSource()){
            int index = context.indexLookup().get(source);
            if(index < minIndex){
                minIndex = index;
            }
        }

        for(AbstractInsnNode source: arg.getConsumers()){
            int index = context.indexLookup().get(source);
            if(index > maxIndex){
                maxIndex = index;
            }
        }

        //Step two: Generate the vars
        Type[] types;
        if(arg.getTransformType() == null){
            types = new Type[]{arg.getType()};
        }else{
            types = arg.transformedTypes().toArray(new Type[0]);
        }

        int[] vars = new int[types.length];

        for(int i = 0; i < vars.length; i++){
            vars[i] = context.variableManager.allocate(minIndex, maxIndex, types[i]);
        }

        //Step three: Store the results
        for(AbstractInsnNode source: arg.getSource()){
            InsnList newInstructions = new InsnList();
            for (int i = vars.length - 1; i >= 0; i--) {
                newInstructions.add(new VarInsnNode(types[i].getOpcode(Opcodes.ISTORE), vars[i]));
            }
            AbstractInsnNode actualSource = context.getActual(source);
            context.target().instructions.insert(actualSource, newInstructions);
        }

        //Step four: Load the results
        BytecodeFactory[] emitters = new BytecodeFactory[vars.length];
        for(int i = 0; i < vars.length; i++){
            int finalI = i;
            emitters[i] = () -> {
                InsnList newInstructions = new InsnList();
                newInstructions.add(new VarInsnNode(types[finalI].getOpcode(Opcodes.ILOAD), vars[finalI]));
                return newInstructions;
            };
        }

        return emitters;
    }

    private void modifyVariableTable(MethodNode methodNode, TransformContext context) {
        if(methodNode.localVariables != null) {
            List<LocalVariableNode> original = methodNode.localVariables;
            List<LocalVariableNode> newLocalVariables = new ArrayList<>();

            for (LocalVariableNode local : original) {
                int codeIndex = context.indexLookup().get(local.start);
                int newIndex = context.varLookup[codeIndex][local.index];

                TransformTrackingValue value = context.analysisResults().frames()[codeIndex].getLocal(local.index);
                if (value.getTransformType() == null || value.getTransform().getSubtype() != TransformSubtype.SubType.NONE) {
                    String desc = value.getTransformType() == null ? value.getType().getDescriptor() : value.getTransform().getSingleType().getDescriptor();
                    newLocalVariables.add(new LocalVariableNode(local.name, desc, local.signature, local.start, local.end, newIndex));
                } else {
                    String[] postfixes = value.getTransformType().getPostfix();
                    int varIndex = newIndex;
                    for (int j = 0; j < postfixes.length; j++) {
                        newLocalVariables.add(new LocalVariableNode(local.name + postfixes[j], value.getTransformType().getTo()[j].getDescriptor(), local.signature, local.start, local.end, varIndex));
                        varIndex += value.getTransformType().getTo()[j].getSize();
                    }
                }
            }

            methodNode.localVariables = newLocalVariables;
        }

        if(methodNode.parameters != null){
            List<ParameterNode> original = methodNode.parameters;
            List<ParameterNode> newParameters = new ArrayList<>();

            int index = 0;
            if((methodNode.access & Opcodes.ACC_STATIC) == 0){
                index++;
            }
            for(ParameterNode param : original){
                TransformTrackingValue value = context.analysisResults.frames()[0].getLocal(index);
                if (value.getTransformType() == null || value.getTransform().getSubtype() != TransformSubtype.SubType.NONE) {
                    newParameters.add(new ParameterNode(param.name, param.access));
                } else {
                    String[] postfixes = value.getTransformType().getPostfix();
                    for (String postfix : postfixes) {
                        newParameters.add(new ParameterNode(param.name + postfix, param.access));
                    }
                }
                index += value.getSize();
            }

            methodNode.parameters = newParameters;
        }
    }

    public void analyzeAllMethods(){
        long startTime = System.currentTimeMillis();
        for(MethodNode methodNode: classNode.methods){
            if((methodNode.access & Opcodes.ACC_NATIVE) != 0){
                throw new IllegalStateException("Cannot analyze/transform native methods");
            }

            if((methodNode.access & Opcodes.ACC_ABSTRACT) != 0){
                //We still want to infer their argument types
                Type[] args = Type.getArgumentTypes(methodNode.desc);

                TransformSubtype[] argTypes = new TransformSubtype[args.length];
                for(int i = 0; i < args.length; i++){
                    argTypes[i] = TransformSubtype.of(null);
                }

                Frame<TransformTrackingValue>[] frames = new Frame[1];

                int numLocals = 0;
                if(!ASMUtil.isStatic(methodNode)){
                    numLocals++;
                }
                for(Type argType: args){
                    numLocals += argType.getSize();
                }
                frames[0] = new Frame<>(numLocals, 0);

                int varIndex = 0;
                if(!ASMUtil.isStatic(methodNode)){
                    frames[0].setLocal(varIndex, new TransformTrackingValue(Type.getObjectType(classNode.name), fieldPseudoValues));
                    varIndex++;
                }

                for(Type argType: args){
                    frames[0].setLocal(varIndex, new TransformTrackingValue(argType, fieldPseudoValues));
                    varIndex += argType.getSize();
                }

                AnalysisResults results = new AnalysisResults(methodNode, argTypes, frames);

                MethodID methodID = new MethodID(classNode.name, methodNode.name, methodNode.desc, MethodID.CallType.VIRTUAL);
                analysisResults.put(methodID, results);

                //Bind previous calls
                for(FutureMethodBinding binding: futureMethodBindings.getOrDefault(methodID, List.of())){
                    TransformTrackingInterpreter.bindValuesToMethod(results, binding.offset(), binding.parameters());
                }

                continue;
            }
            analyzeMethod(methodNode);
        }

        cleanUp();

        for(AnalysisResults results: analysisResults.values()){
            results.print(System.out, false);
        }

        System.out.println("\nField Transforms:");

        for(var entry: fieldPseudoValues.entrySet()){
            if(entry.getValue().getTransformType() == null){
                System.out.println(entry.getKey() + ": [NO CHANGE]");
            }else{
                System.out.println(entry.getKey() + ": " + entry.getValue().getTransformType());
            }
        }

        System.out.println("Finished analysis of " + classNode.name + " in " + (System.currentTimeMillis() - startTime) + "ms");
    }

    public void cleanUp(){
        Map<MethodID, AnalysisResults> newResults = new HashMap<>();

        for(MethodID methodID: analysisResults.keySet()){
            AnalysisResults results = analysisResults.get(methodID);
            boolean isStatic = ASMUtil.isStatic(results.methodNode());

            TransformSubtype[] varTypes = new TransformSubtype[ASMUtil.argumentSize(results.methodNode().desc, isStatic)]; //Indices are local variable indices
            TransformSubtype[] argTypes = new TransformSubtype[ASMUtil.argumentCount(results.methodNode().desc, isStatic)]; //Indices are argument indices

            Frame<TransformTrackingValue> firstFrame = results.frames()[0];
            for(int i = 0; i < varTypes.length; i++){
                TransformTrackingValue local = firstFrame.getLocal(i);
                if(local == null){
                    varTypes[i] = TransformSubtype.of(null);
                }else {
                    varTypes[i] = local.getTransform();
                }
            }

            ASMUtil.varIndicesToArgIndices(varTypes, argTypes, results.methodNode().desc, isStatic);

            config.getInterpreter().checkAndCleanUp();

            AnalysisResults finalResults = new AnalysisResults(results.methodNode(), argTypes, results.frames());
            analysisResults.put(methodID, finalResults);
        }

        //Change field subType in new class
        if(newClassNode != null) {
            for (var entry : fieldPseudoValues.entrySet()) {
                if (entry.getValue().getTransformType() == null) {
                    continue;
                }

                TransformSubtype transformType = entry.getValue().getTransform();
                FieldID fieldID = entry.getKey();
                ASMUtil.changeFieldType(newClassNode, fieldID, transformType.getSingleType(), (m) -> new InsnList());
            }
        }
    }

    private void makeFieldCasts(){
        for(var entry: fieldPseudoValues.entrySet()){
            if(entry.getValue().getTransformType() == null){
                continue;
            }

            TransformSubtype transformType = entry.getValue().getTransform();
            FieldID fieldID = entry.getKey();

            String originalType = entry.getValue().getType().getInternalName();
            String transformedType = transformType.getSingleType().getInternalName();

            ASMUtil.changeFieldType(classNode, fieldID, Type.getObjectType("java/lang/Object"), (method) -> {
                InsnList insnList = new InsnList();
                if(isSynthetic(method)) {
                    insnList.add(new TypeInsnNode(Opcodes.CHECKCAST, transformType.getSingleType().getInternalName()));
                }else{
                    insnList.add(new TypeInsnNode(Opcodes.CHECKCAST, originalType));
                }
                return insnList;
            });
        }
    }

    public AnalysisResults analyzeMethod(MethodNode methodNode){
        long startTime = System.currentTimeMillis();
        config.getInterpreter().reset();
        config.getInterpreter().setResultLookup(analysisResults);
        config.getInterpreter().setFutureBindings(futureMethodBindings);
        config.getInterpreter().setCurrentClass(classNode);
        config.getInterpreter().setFieldBindings(fieldPseudoValues);

        MethodID methodID = new MethodID(classNode.name, methodNode.name, methodNode.desc, null);

        Map<Integer, TransformType> typeHints;
        if(transformInfo != null) {
            typeHints = transformInfo.getTypeHints().get(methodID);
        }else{
            typeHints = null;
        }

        if(typeHints != null){
            config.getInterpreter().setLocalVarOverrides(typeHints);
        }

        try {
            var frames = config.getAnalyzer().analyze(classNode.name, methodNode);
            boolean isStatic = ASMUtil.isStatic(methodNode);

            TransformSubtype[] varTypes = new TransformSubtype[ASMUtil.argumentSize(methodNode.desc, isStatic)]; //Indices are local variable indices
            TransformSubtype[] argTypes = new TransformSubtype[ASMUtil.argumentCount(methodNode.desc, isStatic)]; //Indices are argument indices

            Frame<TransformTrackingValue> firstFrame = frames[0];
            for(int i = 0; i < varTypes.length; i++){
                varTypes[i] = firstFrame.getLocal(i).getTransform();
            }

            ASMUtil.varIndicesToArgIndices(varTypes, argTypes, methodNode.desc, isStatic);

            config.getInterpreter().checkAndCleanUp();

            AnalysisResults results = new AnalysisResults(methodNode, argTypes, frames);
            analysisResults.put(methodID, results);

            //Bind previous calls
            for(FutureMethodBinding binding: futureMethodBindings.getOrDefault(methodID, List.of())){
                TransformTrackingInterpreter.bindValuesToMethod(results, binding.offset(), binding.parameters());
            }

            System.out.println("Analyzed method " + methodID + " in " + (System.currentTimeMillis() - startTime) + "ms");
            return results;
        }catch (AnalyzerException e){
            throw new RuntimeException("Analysis failed", e);
        }
    }

    public void saveTransformedClass(){
        Path outputPath = OUT_DIR.resolve(getTransformed().name + ".class");
        try {
            Files.createDirectories(outputPath.getParent());
            ClassWriter writer = new ClassWriter(0);
            getTransformed().accept(writer);
            Files.write(outputPath, writer.toByteArray());
        }catch (IOException e){
            throw new RuntimeException("Failed to save transformed class", e);
        }
    }

    public void transformMethod(String name, String desc) {
        MethodNode methodNode = classNode.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findAny().orElse(null);
        if(methodNode == null){
            throw new RuntimeException("Method " + name + desc + " not found in class " + classNode.name);
        }
        try {
            transformMethod(methodNode);
        }catch (Exception e){
            throw new RuntimeException("Failed to transform method " + name + desc, e);
        }
    }

    public void transformMethod(String name) {
        List<MethodNode> methods = classNode.methods.stream().filter(m -> m.name.equals(name)).toList();
        if(methods.isEmpty()){
            throw new RuntimeException("Method " + name + " not found in class " + classNode.name);
        }else if(methods.size() > 1){
            throw new RuntimeException("Multiple methods named " + name + " found in class " + classNode.name);
        }else{
            try{
                transformMethod(methods.get(0));
            }catch (Exception e){
                throw new RuntimeException("Failed to transform method " + name + methods.get(0).desc, e);
            }
        }
    }

    public Class<?> loadTransformedClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        getTransformed().accept(writer);
        byte[] bytes = writer.toByteArray();

        String targetName = getTransformed().name.replace('/', '.');

        ClassLoader classLoader = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if(name.equals(targetName)) {
                    return defineClass(name, bytes, 0, bytes.length);
                }else{
                    return super.loadClass(name);
                }
            }
        };

        try {
            return classLoader.loadClass(targetName);
        }catch (ClassNotFoundException e){
            throw new RuntimeException("Failed to load transformed class", e);
        }
    }

    private ClassNode getTransformed(){
        if(newClassNode == null){
            return classNode;
        }else{
            return newClassNode;
        }
    }

    public void transformAllMethods() {
        int size = classNode.methods.size();
        for (int i = 0; i < size; i++) {
            MethodNode methodNode = classNode.methods.get(i);
            if (!methodNode.name.equals("<init>") && !methodNode.name.equals("<clinit>")) {
                try {
                    transformMethod(methodNode);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to transform method " + methodNode.name + methodNode.desc, e);
                }
            }
        }

        cleanUpTransform();
    }

    public void makeConstructor(String desc, InsnList constructor) {
        Type[] args = Type.getArgumentTypes(desc);
        int totalSize = 1;
        for (Type arg : args) {
            totalSize += arg.getSize();
        }

        Type[] newArgs = new Type[args.length + 1];
        newArgs[newArgs.length - 1] = Type.INT_TYPE;
        System.arraycopy(args, 0, newArgs, 0, args.length);
        String newDesc = Type.getMethodDescriptor(Type.VOID_TYPE, newArgs);

        InsnList safetyCheck = new InsnList();
        LabelNode label = new LabelNode();
        safetyCheck.add(new VarInsnNode(Opcodes.ILOAD, totalSize));
        safetyCheck.add(new LdcInsnNode(MAGIC));
        safetyCheck.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, label));
        safetyCheck.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalArgumentException"));
        safetyCheck.add(new InsnNode(Opcodes.DUP));
        safetyCheck.add(new LdcInsnNode("Wrong magic value '"));
        safetyCheck.add(new VarInsnNode(Opcodes.ILOAD, totalSize));
        safetyCheck.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "toHexString", "(I)Ljava/lang/String;", false));
        safetyCheck.add(new LdcInsnNode("'"));
        safetyCheck.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false));
        safetyCheck.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false));
        safetyCheck.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false));
        safetyCheck.add(new InsnNode(Opcodes.ATHROW));
        safetyCheck.add(label);
        safetyCheck.add(new VarInsnNode(Opcodes.ALOAD, 0));
        safetyCheck.add(new InsnNode(Opcodes.ICONST_1));
        safetyCheck.add(new FieldInsnNode(Opcodes.PUTFIELD, classNode.name, isTransformedField.name(), "Z"));

        AbstractInsnNode[] nodes = constructor.toArray();

        //Find super call
        for(AbstractInsnNode node : nodes){
            if(node.getOpcode() == Opcodes.INVOKESPECIAL){
                MethodInsnNode methodNode = (MethodInsnNode) node;
                if(methodNode.owner.equals(classNode.superName)){
                    constructor.insert(safetyCheck);
                    break;
                }
            }
        }

        //Shift variables
        for(AbstractInsnNode node : nodes){
            if(node instanceof VarInsnNode varNode){
                if(varNode.var >= totalSize){
                    varNode.var++;
                }
            }else if(node instanceof IincInsnNode iincNode){
                if(iincNode.var >= totalSize){
                    iincNode.var++;
                }
            }
        }

        MethodNode methodNode = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", newDesc, null, null);
        methodNode.instructions.add(constructor);

        markSynthetic(methodNode, "CONSTRUCTOR", "<init>" + desc);

        newMethods.add(methodNode);
    }

    private void insertAtReturn(MethodNode methodNode, Supplier<InsnList> insn) {
        InsnList instructions = methodNode.instructions;
        AbstractInsnNode[] nodes = instructions.toArray();

        for (AbstractInsnNode node: nodes) {
            if (   node.getOpcode() == Opcodes.RETURN
                || node.getOpcode() == Opcodes.ARETURN
                || node.getOpcode() == Opcodes.IRETURN
                || node.getOpcode() == Opcodes.FRETURN
                || node.getOpcode() == Opcodes.DRETURN
                || node.getOpcode() == Opcodes.LRETURN) {
                instructions.insertBefore(node, insn.get());
            }
        }
    }

    private static void markSynthetic(MethodNode methodNode, String subType, String original){
        List<AnnotationNode> annotations = methodNode.visibleAnnotations;
        if(annotations == null){
            annotations = new ArrayList<>();
            methodNode.visibleAnnotations = annotations;
        }

        AnnotationNode synthetic = new AnnotationNode(Type.getDescriptor(CCSynthetic.class));

        synthetic.values = new ArrayList<>();
        synthetic.values.add("subType");
        synthetic.values.add(subType);
        synthetic.values.add("original");
        synthetic.values.add(original);

        annotations.add(synthetic);
    }

    private static boolean isSynthetic(MethodNode methodNode){
        List<AnnotationNode> annotations = methodNode.visibleAnnotations;
        if(annotations == null){
            return false;
        }

        for(AnnotationNode annotation : annotations){
            if(annotation.desc.equals(Type.getDescriptor(CCSynthetic.class))){
                return true;
            }
        }

        return false;
    }

    public static void emitWarning(String methodOwner, String methodName, String methodDesc){
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        StackTraceElement caller = stackTrace[1];

        String warningID = methodOwner + "." + methodName + methodDesc + " at " + caller.getClassName() + "." + caller.getMethodName() + ":" + caller.getLineNumber();
        if(warnings.add(warningID)){
            System.out.println("[CC] Incorrect Invocation: " + warningID);
        }
    }

    private record TransformContext(MethodNode target, AnalysisResults analysisResults, AbstractInsnNode[] instructions, boolean[] expandedEmitter, boolean[] expandedConsumer, boolean[] removedEmitter, BytecodeFactory[][] syntheticEmitters, int[][] varLookup, TransformSubtype[][] varTypes, VariableManager variableManager, Map<AbstractInsnNode, Integer> indexLookup,
                                    MethodParameterInfo[] methodInfos){

        <T extends AbstractInsnNode> T getActual(T node){
            return (T) instructions[indexLookup.get(node)];
        }
    }
}
