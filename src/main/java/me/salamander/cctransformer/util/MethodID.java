package me.salamander.cctransformer.util;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Objects;

public class MethodID implements Ancestralizable<MethodID>{
    private final Type owner;
    private final String name;
    private final Type descriptor;

    private final CallType callType;

    public MethodID(Type owner, String name, Type descriptor, CallType callType) {
        this.owner = owner;
        this.name = name;
        this.descriptor = descriptor;
        this.callType = callType;
    }

    public MethodID(String owner, String name, String desc, CallType callType) {
        this(Type.getObjectType(owner), name, Type.getMethodType(desc), callType);

    }

    public Type getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public Type getDescriptor() {
        return descriptor;
    }

    public CallType getCallType() {
        return callType;
    }

    public MethodInsnNode callNode() {
        return new MethodInsnNode(callType.getOpcode(), owner.getInternalName(), name, descriptor.getDescriptor(), callType == CallType.INTERFACE);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MethodID methodID = (MethodID) o;
        return Objects.equals(owner, methodID.owner) && Objects.equals(name, methodID.name) && Objects.equals(descriptor, methodID.descriptor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, name, descriptor);
    }

    @Override
    public Type getAssociatedType() {
        return owner;
    }

    @Override
    public MethodID withType(Type type) {
        return new MethodID(type, name, descriptor, callType);
    }

    @Override
    public boolean equalsWithoutType(MethodID other) {
        return Objects.equals(name, other.name) && Objects.equals(descriptor, other.descriptor);
    }

    public enum CallType {
        VIRTUAL(Opcodes.INVOKEVIRTUAL),
        STATIC(Opcodes.INVOKESTATIC),
        SPECIAL(Opcodes.INVOKESPECIAL),
        INTERFACE(Opcodes.INVOKEINTERFACE);

        private final int opcode;

        CallType(int opcode){
            this.opcode = opcode;
        }

        public static CallType fromOpcode(int opcode) {
            return switch (opcode) {
                case Opcodes.INVOKEVIRTUAL -> VIRTUAL;
                case Opcodes.INVOKESTATIC -> STATIC;
                case Opcodes.INVOKESPECIAL -> SPECIAL;
                case Opcodes.INVOKEINTERFACE -> INTERFACE;
                default -> throw new IllegalArgumentException("Unknown opcode " + opcode);
            };
        }

        public int getOpcode(){
            return opcode;
        }

        public int getOffset() {
            if(this == STATIC){
                return 0;
            }
            return 1;
        }
    }
}
