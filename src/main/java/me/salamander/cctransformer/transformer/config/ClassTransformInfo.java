package me.salamander.cctransformer.transformer.config;

import me.salamander.cctransformer.util.MethodID;

import java.util.Map;

public class ClassTransformInfo {
    private final Map<MethodID, Map<Integer, TransformType>> typeHints;

    public ClassTransformInfo(Map<MethodID, Map<Integer, TransformType>> typeHints) {
        this.typeHints = typeHints;
    }

    public Map<MethodID, Map<Integer, TransformType>> getTypeHints() {
        return typeHints;
    }
}
