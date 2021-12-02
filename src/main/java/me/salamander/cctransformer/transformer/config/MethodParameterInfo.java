package me.salamander.cctransformer.transformer.config;

import me.salamander.cctransformer.bytecodegen.BytecodeFactory;
import me.salamander.cctransformer.transformer.analysis.TransformSubtype;
import me.salamander.cctransformer.util.MethodID;
import org.objectweb.asm.Type;

import java.lang.reflect.Parameter;
import java.util.Arrays;

public class MethodParameterInfo{
    private final MethodID method;
    private final TransformSubtype returnType;
    private final TransformSubtype[] parameterTypes;
    private final MethodTransformChecker transformCondition;
    private final BytecodeFactory[] expansion;

    public MethodParameterInfo(MethodID method, TransformSubtype returnType, TransformSubtype[] parameterTypes, MethodTransformChecker.Minimum[] minimums, BytecodeFactory[] expansion) {
        this.method = method;
        this.returnType = returnType;
        this.parameterTypes = parameterTypes;
        this.transformCondition = new MethodTransformChecker(this, minimums);
        this.expansion = expansion;
    }

    public MethodParameterInfo(MethodID method, TransformSubtype returnType, TransformSubtype[] parameterTypes, MethodTransformChecker.Minimum[] minimums) {
        this.method = method;
        this.returnType = returnType;
        this.parameterTypes = parameterTypes;
        this.transformCondition = new MethodTransformChecker(this, minimums);
        this.expansion = null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        if(returnType == null){
            sb.append(getOnlyName(method.getDescriptor().getReturnType()));
        }else{
            sb.append('[');
            sb.append(returnType.getTransformType().getName());
            sb.append(']');
        }

        Type[] types = method.getDescriptor().getArgumentTypes();

        sb.append(" ");
        sb.append(getOnlyName(method.getOwner()));
        sb.append("#");
        sb.append(method.getName());
        sb.append("(");
        for(int i = 0; i < parameterTypes.length; i++){
            TransformType type = parameterTypes[i].getTransformType();
            if(type != null){
                sb.append('[');
                sb.append(type.getName());
                sb.append(']');
            }else{
                sb.append(getOnlyName(types[i]));
            }
            if(i != parameterTypes.length - 1){
                sb.append(", ");
            }
        }

        sb.append(")");
        return sb.toString();
    }

    private static String getOnlyName(Type type){
        String name = type.getClassName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    public MethodID getMethod() {
        return method;
    }

    public TransformSubtype getReturnType() {
        return returnType;
    }

    public TransformSubtype[] getParameterTypes() {
        return parameterTypes;
    }

    public MethodTransformChecker getTransformCondition() {
        return transformCondition;
    }

    public BytecodeFactory[] getExpansion() {
        return expansion;
    }
}
