package me.salamander.cctransformer.transformer.analysis;

import me.salamander.cctransformer.transformer.config.Config;
import me.salamander.cctransformer.transformer.config.TransformType;
import org.objectweb.asm.Type;

import java.util.*;

public class TransformSubtype {
    private final TransformTypePtr transformType;
    private int arrayDimensionality;
    private Type subtype;

    public TransformSubtype(TransformTypePtr transformType, int arrayDimensionality, Type subtype) {
        this.transformType = transformType;
        this.arrayDimensionality = arrayDimensionality;
        this.subtype = subtype;
    }

    public TransformType getTransformType() {
        return transformType.getValue();
    }

    TransformTypePtr getTransformTypePtr() {
        return transformType;
    }

    public int getArrayDimensionality() {
        return arrayDimensionality;
    }

    public Type getSubtype() {
        return subtype;
    }

    void setArrayDimensionality(int arrayDimensionality) {
        this.arrayDimensionality = arrayDimensionality;
    }

    public static TransformSubtype createDefault() {
        return new TransformSubtype(new TransformTypePtr(null), 0, Type.NONE);
    }

    void setSubType(Type transformType) {
        this.subtype = transformType;
    }

    public static TransformSubtype fromString(String s, Map<String, TransformType> transformLookup) {
        int arrIndex = s.indexOf('[');
        int arrDimensionality = 0;
        if (arrIndex != -1) {
            arrDimensionality = (s.length() - arrIndex) / 2;
            s = s.substring(0, arrIndex);
        }

        String[] parts = s.split(" ");
        Type type;
        TransformType transformType = transformLookup.get(parts[0]);
        if (parts.length == 1) {
            type = Type.NONE;
        }else{
            type = Type.fromString(parts[1]);
        }
        return new TransformSubtype(new TransformTypePtr(transformType), arrDimensionality, type);
    }

    public static TransformSubtype of(TransformType type){
        return new TransformSubtype(new TransformTypePtr(type), 0, Type.NONE);
    }

    public static TransformSubtype of(TransformType transformType, String type) {
        return new TransformSubtype(new TransformTypePtr(transformType), 0, Type.fromString(type));
    }

    public org.objectweb.asm.Type getRawType(TransformType transformType) {
        return switch (this.subtype){
            case NONE -> transformType.getFrom();
            case PREDICATE -> transformType.getOriginalPredicateType();
            case CONSUMER -> transformType.getOriginalConsumerType();
        };
    }

    public static Type getSubType(org.objectweb.asm.Type type){
        while(true) {
            if (regularTypes.contains(type)) {
                return Type.NONE;
            } else if (consumerTypes.contains(type)) {
                return Type.CONSUMER;
            } else if (predicateTypes.contains(type)) {
                return Type.PREDICATE;
            }

            if(type.getSort() != org.objectweb.asm.Type.ARRAY){
                break;
            }else{
                type = type.getElementType();
            }
        }


        return Type.NONE;
    }

    enum Type {
        NONE,
        PREDICATE,
        CONSUMER;

        public static Type fromString(String part) {
            return switch (part.toLowerCase(Locale.ROOT)) {
                case "predicate" -> PREDICATE;
                case "consumer" -> CONSUMER;
                default -> {
                    System.err.println("Unknown subtype: " + part);
                    yield NONE;
                }
            };
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TransformSubtype that = (TransformSubtype) o;
        return arrayDimensionality == that.arrayDimensionality && transformType.getValue() == that.transformType.getValue() && subtype == that.subtype;
    }

    @Override
    public int hashCode() {
        return Objects.hash(transformType, arrayDimensionality, subtype);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        if(transformType.getValue() == null){
            if(subtype == Type.NONE){
                return "No transform";
            }else{
                sb.append(subtype.name().toLowerCase(Locale.ROOT));
                sb.append(" candidate");
                return sb.toString();
            }
        }

        sb.append(transformType.getValue());

        if(subtype != Type.NONE){
            sb.append(" ");
            sb.append(subtype.name().toLowerCase(Locale.ROOT));
        }

        if(arrayDimensionality > 0){
            for(int i = 0; i < arrayDimensionality; i++){
                sb.append("[]");
            }
        }

        return sb.toString();
    }

    private static final Set<org.objectweb.asm.Type> regularTypes = new HashSet<>();
    private static final Set<org.objectweb.asm.Type> consumerTypes = new HashSet<>();
    private static final Set<org.objectweb.asm.Type> predicateTypes = new HashSet<>();

    public static void init(Config config){
        for(var entry: config.getTypes().entrySet()){
            var type = entry.getValue();
            regularTypes.add(type.getFrom());
            consumerTypes.add(type.getOriginalConsumerType());
            predicateTypes.add(type.getOriginalPredicateType());
        }
    }
}
