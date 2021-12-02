package me.salamander.cctransformer.transformer.config;

import me.salamander.cctransformer.bytecodegen.BytecodeFactory;
import me.salamander.cctransformer.transformer.analysis.TransformSubtype;
import me.salamander.cctransformer.util.ASMUtil;
import me.salamander.cctransformer.util.MethodID;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.InsnList;

import java.util.Map;

public class TransformType {
    private final String id;
    private final Type from;
    private final Type[] to;
    private final MethodID[] fromOriginal;
    private final MethodID toOriginal;

    private final Type originalPredicateType;
    private final Type transformedPredicateType;

    private final Type originalConsumerType;
    private final Type transformedConsumerType;

    public TransformType(String id, Type from, Type[] to, MethodID[] fromOriginal, MethodID toOriginal, Type originalPredicateType, Type transformedPredicateType, Type originalConsumerType, Type transformedConsumerType) {
        this.id = id;
        this.from = from;
        this.to = to;
        this.fromOriginal = fromOriginal;
        this.toOriginal = toOriginal;
        this.originalPredicateType = originalPredicateType;
        this.transformedPredicateType = transformedPredicateType;
        this.originalConsumerType = originalConsumerType;
        this.transformedConsumerType = transformedConsumerType;
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder("Transform Type " + id + "[" + ASMUtil.onlyClassName(from.getClassName()) + " -> (");

        for (int i = 0; i < to.length; i++) {
            str.append(ASMUtil.onlyClassName(to[i].getClassName()));
            if(i < to.length - 1) {
                str.append(", ");
            }
        }

        str.append(")]");

        return str.toString();
    }

    public void addParameterInfoTo(Map<MethodID, MethodParameterInfo> parameterInfo) {
        if(fromOriginal != null) {
            for (MethodID methodID : fromOriginal) {
                MethodParameterInfo info = new MethodParameterInfo(methodID, TransformSubtype.of(null), new TransformSubtype[]{TransformSubtype.of(this)}, null);
                parameterInfo.put(methodID, info);
            }
        }

        BytecodeFactory[] expansions = new BytecodeFactory[to.length];
        for (int i = 0; i < to.length; i++) {
            expansions[i] = InsnList::new;
        }

        if(toOriginal != null) {
            TransformSubtype[] to = new TransformSubtype[this.to.length];
            for (int i = 0; i < to.length; i++) {
                to[i] = TransformSubtype.of(null);
            }
            MethodParameterInfo info = new MethodParameterInfo(toOriginal, TransformSubtype.of(this), to, null, expansions);
            parameterInfo.put(toOriginal, info);
        }

        if(originalPredicateType != null) {
            MethodID predicateID = new MethodID(originalPredicateType, "test", Type.getMethodType(Type.BOOLEAN_TYPE, from), MethodID.CallType.INTERFACE);

            TransformSubtype[] argTypes = new TransformSubtype[]{TransformSubtype.of(this, "predicate"), TransformSubtype.of(this)};

            MethodTransformChecker.Minimum[] minimums = new MethodTransformChecker.Minimum[]{
                    new MethodTransformChecker.Minimum(TransformSubtype.of(null), TransformSubtype.of(this, "predicate"), TransformSubtype.of(null)),
                    new MethodTransformChecker.Minimum(TransformSubtype.of(null), TransformSubtype.of(null), TransformSubtype.of(this))
            };

            MethodParameterInfo info = new MethodParameterInfo(predicateID, TransformSubtype.of(null), argTypes, minimums);
            parameterInfo.put(predicateID, info);
        }

        if(originalConsumerType != null) {
            MethodID consumerID = new MethodID(originalConsumerType, "accept", Type.getMethodType(Type.VOID_TYPE, from), MethodID.CallType.INTERFACE);

            TransformSubtype[] argTypes = new TransformSubtype[]{TransformSubtype.of(this, "consumer"), TransformSubtype.of(this)};

            MethodTransformChecker.Minimum[] minimums = new MethodTransformChecker.Minimum[]{
                    new MethodTransformChecker.Minimum(TransformSubtype.of(null), TransformSubtype.of(this, "consumer"), TransformSubtype.of(null)),
                    new MethodTransformChecker.Minimum(TransformSubtype.of(null), TransformSubtype.of(null), TransformSubtype.of(this))
            };

            MethodParameterInfo info = new MethodParameterInfo(consumerID, TransformSubtype.of(null), argTypes, minimums);
            parameterInfo.put(consumerID, info);
        }
    }

    public String getName() {
        return id;
    }

    public Type getFrom() {
        return from;
    }

    public Type[] getTo() {
        return to;
    }

    public MethodID[] getFromOriginal() {
        return fromOriginal;
    }

    public MethodID getToOriginal() {
        return toOriginal;
    }

    public Type getOriginalPredicateType() {
        return originalPredicateType;
    }

    public Type getTransformedPredicateType() {
        return transformedPredicateType;
    }

    public Type getOriginalConsumerType() {
        return originalConsumerType;
    }

    public Type getTransformedConsumerType() {
        return transformedConsumerType;
    }
}
