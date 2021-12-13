package me.salamander.cctransformer.transformer.analysis;

import me.salamander.cctransformer.transformer.config.MethodParameterInfo;
import me.salamander.cctransformer.transformer.config.TransformType;
import me.salamander.cctransformer.util.ASMUtil;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Frame;

import java.io.PrintStream;

public record AnalysisResults(MethodNode methodNode, TransformSubtype[] argTypes, Frame<TransformTrackingValue>[] frames) {

    public void print(PrintStream out, boolean printFrames) {
        out.println("Analysis Results for " + methodNode.name);
        out.println("  Arg Types:");
        for (TransformSubtype argType : argTypes) {
            out.println("    " + argType);
        }

        if (printFrames) {
            out.println("  Frames:");
            for (int i = 0; i < frames.length; i++) {
                Frame<TransformTrackingValue> frame = frames[i];
                if (frame != null) {
                    out.println("    Frame " + i);
                    out.println("      Stack:");
                    for (int j = 0; j < frames[i].getStackSize(); j++) {
                        out.println("        " + frames[i].getStack(j));
                    }
                    out.println("      Locals:");
                    for (int j = 0; j < frames[i].getLocals(); j++) {
                        out.println("        " + frames[i].getLocal(j));
                    }
                }
            }
        }
    }

    public String getNewDesc() {
        TransformSubtype[] types = argTypes;
        if(!ASMUtil.isStatic(methodNode)) {
            types = new TransformSubtype[types.length - 1];
            System.arraycopy(argTypes, 1, types, 0, types.length);
        }

        return MethodParameterInfo.getNewDesc(TransformSubtype.of(null), types, methodNode.desc);
    }
}
