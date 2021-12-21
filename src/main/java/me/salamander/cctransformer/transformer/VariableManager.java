package me.salamander.cctransformer.transformer;

import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.List;

public class VariableManager {
    private final int baseline;
    private final int maxLength;
    private final List<boolean[]> variables = new ArrayList<>();

    public VariableManager(int maxLocals, int maxLength){
        this.baseline = maxLocals;
        this.maxLength = maxLength;
    }

    public int allocateSingle(int from, int to){
        int level = 0;
        while(true){
            if(level >= variables.size()){
                variables.add(new boolean[maxLength]);
            }
            boolean[] var = variables.get(level);

            //Check that all of it is free
            boolean free = true;
            for(int i = from; i < to; i++){
                if(var[i]){
                    free = false;
                    break;
                }
            }

            if(free){
                //Mark it as used
                for(int i = from; i < to; i++){
                    var[i] = true;
                }

                return level + baseline;
            }

            level++;
        }
    }

    public int allocateDouble(int from, int to){
        int level = 0;
        while(true){
            while(level + 1 >= variables.size()){
                variables.add(new boolean[maxLength]);
            }

            boolean[] var1 = variables.get(level);
            boolean[] var2 = variables.get(level + 1);

            //Check that all of it is free
            boolean free = true;
            for(int i = from; i < to; i++){
                if(var1[i] || var2[i]){
                    free = false;
                    break;
                }
            }

            if(free){
                //Mark it as used
                for(int i = from; i < to; i++){
                    var1[i] = true;
                    var2[i] = true;
                }

                return level + baseline;
            }

            level++;
        }
    }

    public int allocate(int minIndex, int maxIndex, Type subType) {
        if(subType.getSort() == Type.DOUBLE || subType.getSort() == Type.LONG){
            return allocateDouble(minIndex, maxIndex);
        }else{
            return allocateSingle(minIndex, maxIndex);
        }
    }
}
