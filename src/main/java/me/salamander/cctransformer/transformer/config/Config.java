package me.salamander.cctransformer.transformer.config;

import me.salamander.cctransformer.transformer.analysis.AnalysisResults;
import me.salamander.cctransformer.transformer.analysis.TransformSubtype;
import me.salamander.cctransformer.transformer.analysis.TransformTrackingInterpreter;
import me.salamander.cctransformer.transformer.analysis.TransformTrackingValue;
import me.salamander.cctransformer.util.AncestorHashMap;
import me.salamander.cctransformer.util.MethodID;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.analysis.Analyzer;

import java.io.PrintStream;
import java.util.Map;

public class Config {
    private final HierarchyTree hierarchy;
    private final Map<String, TransformType> types;
    private final Map<MethodID, MethodParameterInfo> methodParameterInfo;
    private final Map<Type, ClassTransformInfo> classes;

    private TransformTrackingInterpreter interpreter;
    private Analyzer<TransformTrackingValue> analyzer;

    public Config(HierarchyTree hierarchy, Map<String, TransformType> transformTypeMap, AncestorHashMap<MethodID, MethodParameterInfo> parameterInfo, Map<Type, ClassTransformInfo> classes) {
        this.types = transformTypeMap;
        this.methodParameterInfo = parameterInfo;
        this.hierarchy = hierarchy;
        this.classes = classes;

        TransformSubtype.init(this);
    }

    public void print(PrintStream out){
        System.out.println("Hierarchy:");
        hierarchy.print(out);

        for(Map.Entry<String, TransformType> entry : types.entrySet()){
            out.println(entry.getValue());
        }

        System.out.println("\nMethod Parameter Info:");

        for(Map.Entry<MethodID, MethodParameterInfo> entry : methodParameterInfo.entrySet()){
            out.println(entry.getValue());
        }
    }

    public HierarchyTree getHierarchy() {
        return hierarchy;
    }

    public Map<String, TransformType> getTypes() {
        return types;
    }

    public Map<MethodID, MethodParameterInfo> getMethodParameterInfo() {
        return methodParameterInfo;
    }

    public TransformTrackingInterpreter getInterpreter(){
        if(interpreter == null){
            interpreter = new TransformTrackingInterpreter(Opcodes.ASM9, this);
        }

        return interpreter;
    }

    public Analyzer<TransformTrackingValue> getAnalyzer(){
        if(analyzer == null){
            analyzer = new Analyzer<>(getInterpreter());
        }

        return analyzer;
    }

    public Map<Type, ClassTransformInfo> getClasses() {
        return classes;
    }
}
