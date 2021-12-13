package me.salamander.cctransformer.transformer.config;

import me.salamander.cctransformer.bytecodegen.BytecodeFactory;

import java.util.List;

public class MethodReplacement {
    private final BytecodeFactory[] bytecodeFactories;
    private final boolean changeParameters;
    private final List<Integer>[][] parameterIndexes;

    public MethodReplacement(BytecodeFactory factory){
        this.bytecodeFactories = new BytecodeFactory[]{factory};
        this.parameterIndexes = null;
        this.changeParameters = false;
    }

    public MethodReplacement(BytecodeFactory[] bytecodeFactories, List<Integer>[][] parameterIndexes) {
        this.bytecodeFactories = bytecodeFactories;
        this.parameterIndexes = parameterIndexes;
        this.changeParameters = true;
    }

    public BytecodeFactory[] getBytecodeFactories() {
        return bytecodeFactories;
    }

    public boolean changeParameters() {
        return changeParameters;
    }

    public List<Integer>[][] getParameterIndexes() {
        return parameterIndexes;
    }
}
