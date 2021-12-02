package me.salamander.cctransformer.transformer;

import me.salamander.cctransformer.transformer.analysis.*;
import me.salamander.cctransformer.transformer.config.ClassTransformInfo;
import me.salamander.cctransformer.transformer.config.Config;
import me.salamander.cctransformer.transformer.config.TransformType;
import me.salamander.cctransformer.util.ASMUtil;
import me.salamander.cctransformer.util.AncestorHashMap;
import me.salamander.cctransformer.util.FieldID;
import me.salamander.cctransformer.util.MethodID;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TypeTransformer {
    private final Config config;
    private final ClassNode classNode;
    private final Map<MethodID, AnalysisResults> analysisResults = new HashMap<>();
    private final Map<MethodID, List<FutureMethodBinding>> futureMethodBindings = new HashMap<>();
    private final AncestorHashMap<FieldID, TransformTrackingValue> fieldPseudoValues;
    private final ClassTransformInfo transformInfo;

    public TypeTransformer(Config config, ClassNode classNode) {
        this.config = config;
        this.classNode = classNode;
        this.fieldPseudoValues = new AncestorHashMap<>(config.getHierarchy());

        //Create field pseudo values
        for(var field: classNode.fields){
            TransformTrackingValue value = new TransformTrackingValue(Type.getType(field.desc), fieldPseudoValues);
            fieldPseudoValues.put(new FieldID(Type.getObjectType(classNode.name), field.name, Type.getType(field.desc)), value);
        }

        this.transformInfo = config.getClasses().get(Type.getObjectType(classNode.name));
    }

    public void analyzeAllMethods(){
        for(MethodNode methodNode: classNode.methods){
            if((methodNode.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
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

        System.out.println("Finished analysis of " + classNode.name);
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
                varTypes[i] = firstFrame.getLocal(i).getTransform();
            }

            ASMUtil.varIndicesToArgIndices(varTypes, argTypes, results.methodNode().desc, isStatic);

            config.getInterpreter().checkAndCleanUp();

            AnalysisResults finalResults = new AnalysisResults(results.methodNode(), results.returnType(), argTypes, results.frames());
            analysisResults.put(methodID, finalResults);
        }
    }

    public AnalysisResults analyzeMethod(MethodNode methodNode){
        config.getInterpreter().reset();
        config.getInterpreter().setResultLookup(analysisResults);
        config.getInterpreter().setFutureBindings(futureMethodBindings);
        config.getInterpreter().setCurrentClass(classNode);
        config.getInterpreter().setFieldBindings(fieldPseudoValues);

        MethodID methodID = new MethodID(classNode.name, methodNode.name, methodNode.desc, null);

        Map<Integer, TransformType> typeHints = transformInfo.getTypeHints().get(methodID);
        if(typeHints != null){
            config.getInterpreter().setLocalVarOverrides(typeHints);
        }

        try {
            var frames = config.getAnalyzer().analyze(classNode.name, methodNode);
            TransformType returnType = config.getInterpreter().getReturnType();
            boolean isStatic = ASMUtil.isStatic(methodNode);

            TransformSubtype[] varTypes = new TransformSubtype[ASMUtil.argumentSize(methodNode.desc, isStatic)]; //Indices are local variable indices
            TransformSubtype[] argTypes = new TransformSubtype[ASMUtil.argumentCount(methodNode.desc, isStatic)]; //Indices are argument indices

            Frame<TransformTrackingValue> firstFrame = frames[0];
            for(int i = 0; i < varTypes.length; i++){
                varTypes[i] = firstFrame.getLocal(i).getTransform();
            }

            ASMUtil.varIndicesToArgIndices(varTypes, argTypes, methodNode.desc, isStatic);

            config.getInterpreter().checkAndCleanUp();

            AnalysisResults results = new AnalysisResults(methodNode, returnType, argTypes, frames);
            analysisResults.put(methodID, results);

            //Bind previous calls
            for(FutureMethodBinding binding: futureMethodBindings.getOrDefault(methodID, List.of())){
                TransformTrackingInterpreter.bindValuesToMethod(results, binding.offset(), binding.parameters());
            }

            return results;
        }catch (AnalyzerException e){
            throw new RuntimeException("Analysis failed", e);
        }
    }
}
