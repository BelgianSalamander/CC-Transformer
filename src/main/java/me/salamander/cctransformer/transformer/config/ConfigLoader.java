package me.salamander.cctransformer.transformer.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.salamander.cctransformer.FabricMappingResolver;
import me.salamander.cctransformer.bytecodegen.BytecodeFactory;
import me.salamander.cctransformer.bytecodegen.JSONBytecodeFactory;
import me.salamander.cctransformer.transformer.analysis.TransformSubtype;
import me.salamander.cctransformer.util.AncestorHashMap;
import me.salamander.cctransformer.util.MethodID;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.fabricmc.loader.impl.launch.MappingConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.Type;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class ConfigLoader {
    public static Config loadConfig(InputStream is){
        JsonParser parser = new JsonParser();
        JsonObject root = parser.parse(new InputStreamReader(is)).getAsJsonObject();

        MappingResolver map = getMapper();

        HierarchyTree hierarchy = new HierarchyTree();
        loadHierarchy(hierarchy, root.get("hierarchy").getAsJsonObject(), map, null);

        Map<String, MethodID> methodIDMap = loadMethodDefinitions(root.get("method_definitions"), map);
        Map<String, TransformType> transformTypeMap = loadTransformTypes(root.get("types"), map, methodIDMap);
        AncestorHashMap<MethodID, MethodParameterInfo> parameterInfo = loadMethodParameterInfo(root.get("methods"), map, methodIDMap, transformTypeMap, hierarchy);
        Map<Type, ClassTransformInfo> classes = loadClassInfo(root.get("classes"), map, methodIDMap, transformTypeMap, parameterInfo, hierarchy);

        for(TransformType type : transformTypeMap.values()){
            type.addParameterInfoTo(parameterInfo);
        }

        Config config = new Config(
                hierarchy,
                transformTypeMap,
                parameterInfo,
                classes
        );

        return config;
    }

    private static Map<Type, ClassTransformInfo> loadClassInfo(JsonElement classes, MappingResolver map, Map<String, MethodID> methodIDMap, Map<String, TransformType> transformTypeMap, AncestorHashMap<MethodID, MethodParameterInfo> parameterInfo, HierarchyTree hierarchy) {
        JsonArray arr = classes.getAsJsonArray();
        Map<Type, ClassTransformInfo> classInfo = new HashMap<>();
        for(JsonElement element : arr){
            JsonObject obj = element.getAsJsonObject();
            Type type = remapType(Type.getObjectType(obj.get("class").getAsString()), map, false);

            JsonArray typeHintsArr = obj.get("type_hints").getAsJsonArray();
            Map<MethodID, Map<Integer, TransformType>> typeHints = new HashMap<>();
            for(JsonElement typeHint : typeHintsArr){
                MethodID method = loadMethodIDFromLookup(typeHint.getAsJsonObject().get("method"), map, methodIDMap);
                Map<Integer, TransformType> paramTypes = new HashMap<>();
                JsonArray paramTypesArr = typeHint.getAsJsonObject().get("types").getAsJsonArray();
                for (int i = 0; i < paramTypesArr.size(); i++) {
                    JsonElement paramType = paramTypesArr.get(i);
                    if(!paramType.isJsonNull()){
                        paramTypes.put(i, transformTypeMap.get(paramType.getAsString()));
                    }
                }
                typeHints.put(method, paramTypes);
            }

            ClassTransformInfo info = new ClassTransformInfo(typeHints);
            classInfo.put(type, info);
        }

        return classInfo;
    }

    private static void loadHierarchy(HierarchyTree hierarchy, JsonObject descendants, MappingResolver map, Type parent) {
        for(Map.Entry<String, JsonElement> entry : descendants.entrySet()){
            if(entry.getKey().equals("__interfaces")){
                JsonArray arr = entry.getValue().getAsJsonArray();
                for(JsonElement element : arr){
                    Type type = remapType(Type.getObjectType(element.getAsString()), map, false);
                    hierarchy.addInterface(type, parent);
                }
            }else {
                Type type = remapType(Type.getObjectType(entry.getKey()), map, false);
                hierarchy.addNode(type, parent);
                loadHierarchy(hierarchy, entry.getValue().getAsJsonObject(), map, type);
            }
        }
    }

    private static AncestorHashMap<MethodID, MethodParameterInfo> loadMethodParameterInfo(JsonElement methods, MappingResolver map, Map<String, MethodID> methodIDMap, Map<String, TransformType> transformTypes, HierarchyTree hierarchy) {
        final AncestorHashMap<MethodID, MethodParameterInfo> parameterInfo = new AncestorHashMap<>(hierarchy);

        if(methods == null) return parameterInfo;

        if(!methods.isJsonArray()){
            System.err.println("Method parameter info is not an array. Cannot read it");
            return parameterInfo;
        }

        for(JsonElement method : methods.getAsJsonArray()){
            JsonObject obj = method.getAsJsonObject();
            MethodID methodID = loadMethodIDFromLookup(obj.get("method"), map, methodIDMap);
            JsonArray paramsJson = obj.get("parameters").getAsJsonArray();
            TransformSubtype[] params = new TransformSubtype[paramsJson.size()];
            for(int i = 0; i < paramsJson.size(); i++){
                JsonElement param = paramsJson.get(i);
                if(param.isJsonPrimitive()){
                    params[i] = TransformSubtype.fromString(param.getAsString(), transformTypes);
                }
            }

            TransformSubtype returnType = null;
            JsonElement returnTypeJson = obj.get("return");
            BytecodeFactory[] expansion = null;
            if(returnTypeJson != null){
                if(returnTypeJson.isJsonPrimitive()) {
                    returnType = TransformSubtype.fromString(returnTypeJson.getAsString(), transformTypes);

                    JsonArray expansionJson = obj.get("expansion").getAsJsonArray();
                    if (expansionJson.size() != returnType.getTransformType().getTo().length) {
                        System.err.println("Expansion array size does not match return type size");
                        continue;
                    }

                    expansion = new BytecodeFactory[expansionJson.size()];
                    for (int i = 0; i < expansionJson.size(); i++) {
                        JsonElement exp = expansionJson.get(i);
                        expansion[i] = new JSONBytecodeFactory(exp.getAsJsonArray(), map, methodIDMap);
                    }

                }
            }

            JsonElement minimumsJson = obj.get("minimums");
            MethodTransformChecker.Minimum[] minimums = null;
            if(minimumsJson != null){
                if(!minimumsJson.isJsonArray()){
                    System.err.println("Minimums are not an array. Cannot read them");
                    continue;
                }
                minimums = new MethodTransformChecker.Minimum[minimumsJson.getAsJsonArray().size()];
                for(int i = 0; i < minimumsJson.getAsJsonArray().size(); i++){
                    JsonObject minimum = minimumsJson.getAsJsonArray().get(i).getAsJsonObject();

                    TransformSubtype minimumReturnType;
                    if(minimum.has("return")){
                        minimumReturnType = TransformSubtype.fromString(minimum.get("return").getAsString(), transformTypes);
                    }else{
                        minimumReturnType = TransformSubtype.of(null);
                    }

                    TransformSubtype[] argTypes = new TransformSubtype[minimum.get("parameters").getAsJsonArray().size()];
                    for(int j = 0; j < argTypes.length; j++){
                        JsonElement argType = minimum.get("parameters").getAsJsonArray().get(j);
                        if(!argType.isJsonNull()){
                            argTypes[j] = TransformSubtype.fromString(argType.getAsString(), transformTypes);
                        }else{
                            argTypes[j] = TransformSubtype.of(null);
                        }
                    }

                    minimums[i] = new MethodTransformChecker.Minimum(minimumReturnType, argTypes);
                }
            }

            MethodParameterInfo info = new MethodParameterInfo(methodID, returnType, params, minimums, expansion);
            parameterInfo.put(methodID, info);
        }

        return parameterInfo;
    }

    private static MethodID loadMethodIDFromLookup(JsonElement method, MappingResolver map, Map<String, MethodID> methodIDMap) {
        if(method.isJsonPrimitive()){
            if(methodIDMap.containsKey(method.getAsString())){
                return methodIDMap.get(method.getAsString());
            }
        }

        return loadMethodID(method, map, null);
    }

    private static Map<String, TransformType> loadTransformTypes(JsonElement typeJson, MappingResolver map, Map<String, MethodID> methodIDMap) {
        Map<String, TransformType> types = new HashMap<>();

        JsonArray typeArray = typeJson.getAsJsonArray();
        for(JsonElement type : typeArray){
            JsonObject obj = type.getAsJsonObject();
            String id = obj.get("id").getAsString();

            Type original = remapType(Type.getType(obj.get("original").getAsString()), map, false);
            JsonArray transformedTypesArray = obj.get("transformed").getAsJsonArray();
            Type[] transformedTypes = new Type[transformedTypesArray.size()];
            for(int i = 0; i < transformedTypesArray.size(); i++){
                transformedTypes[i] = remapType(Type.getType(transformedTypesArray.get(i).getAsString()), map, false);
            }

            JsonElement fromOriginalJson = obj.get("from_original");
            MethodID[] fromOriginal = null;
            if(fromOriginalJson != null) {
                JsonArray fromOriginalArray = fromOriginalJson.getAsJsonArray();
                fromOriginal = new MethodID[fromOriginalArray.size()];
                if (fromOriginalArray.size() != transformedTypes.length) {
                    throw new IllegalArgumentException("Number of from_original methods does not match number of transformed types");
                }
                for (int i = 0; i < fromOriginalArray.size(); i++) {
                    JsonElement fromOriginalElement = fromOriginalArray.get(i);
                    if (fromOriginalElement.isJsonPrimitive()) {
                        fromOriginal[i] = methodIDMap.get(fromOriginalElement.getAsString());
                    }

                    if (fromOriginal[i] == null) {
                        fromOriginal[i] = loadMethodID(fromOriginalArray.get(i), map, null);
                    }
                }
            }

            MethodID toOriginal = null;
            JsonElement toOriginalJson = obj.get("to_original");
            if(toOriginalJson != null){
                toOriginal = loadMethodIDFromLookup(obj.get("to_original"), map, methodIDMap);
            }

            Type originalPredicateType = null;
            JsonElement originalPredicateTypeJson = obj.get("original_predicate");
            if(originalPredicateTypeJson != null){
                originalPredicateType = remapType(Type.getObjectType(originalPredicateTypeJson.getAsString()), map, false);
            }

            Type transformedPredicateType = null;
            JsonElement transformedPredicateTypeJson = obj.get("transformed_predicate");
            if(transformedPredicateTypeJson != null){
                transformedPredicateType = remapType(Type.getObjectType(transformedPredicateTypeJson.getAsString()), map, false);
            }

            Type originalConsumerType = null;
            JsonElement originalConsumerTypeJson = obj.get("original_consumer");
            if(originalConsumerTypeJson != null){
                originalConsumerType = remapType(Type.getObjectType(originalConsumerTypeJson.getAsString()), map, false);
            }

            Type transformedConsumerType = null;
            JsonElement transformedConsumerTypeJson = obj.get("transformed_consumer");
            if(transformedConsumerTypeJson != null){
                transformedConsumerType = remapType(Type.getObjectType(transformedConsumerTypeJson.getAsString()), map, false);
            }

            TransformType transformType = new TransformType(id, original, transformedTypes, fromOriginal, toOriginal, originalPredicateType, transformedPredicateType, originalConsumerType, transformedConsumerType);
            types.put(id, transformType);
        }

        return types;
    }

    private static Map<String, MethodID> loadMethodDefinitions(JsonElement methodMap, MappingResolver map) {
        Map<String, MethodID> methodIDMap = new HashMap<>();

        if(methodMap == null) return methodIDMap;

        if(!methodMap.isJsonArray()){
            System.err.println("Method ID map is not an array. Cannot read it");
            return methodIDMap;
        }

        for(JsonElement method : methodMap.getAsJsonArray()){
            JsonObject obj = method.getAsJsonObject();
            String id = obj.get("id").getAsString();
            MethodID methodID = loadMethodID(obj.get("method"), map, null);

            methodIDMap.put(id, methodID);
        }

        return methodIDMap;
    }

    public static MethodID loadMethodID(JsonElement method, @Nullable MappingResolver map, MethodID.@Nullable CallType defaultCallType) {
        MethodID methodID;
        if(method.isJsonPrimitive()){
            String id = method.getAsString();
            String[] parts = id.split(" ");

            MethodID.CallType callType;
            int nameIndex;
            int descIndex;
            if(parts.length == 3){
                char callChar = parts[0].charAt(0);
                callType = switch (callChar){
                    case 'v' -> MethodID.CallType.VIRTUAL;
                    case 's' -> MethodID.CallType.STATIC;
                    case 'i' -> MethodID.CallType.INTERFACE;
                    case 'S' -> MethodID.CallType.SPECIAL;
                    default -> {
                        System.err.println("Invalid call type: " + callChar + ". Using default VIRTUAL type");
                        yield MethodID.CallType.VIRTUAL;
                    }
                };

                nameIndex = 1;
                descIndex = 2;
            }else{
                callType = MethodID.CallType.VIRTUAL;
                nameIndex = 0;
                descIndex = 1;
            }

            if(defaultCallType != null){
                callType = defaultCallType;
            }

            String desc = parts[descIndex];

            String[] ownerAndName = parts[nameIndex].split("#");
            String owner = ownerAndName[0];
            String name = ownerAndName[1];

            methodID = new MethodID(Type.getObjectType(owner), name, Type.getMethodType(desc), callType);
        }else{
            String owner = method.getAsJsonObject().get("owner").getAsString();
            String name = method.getAsJsonObject().get("name").getAsString();
            String desc = method.getAsJsonObject().get("desc").getAsString();
            String callTypeStr = method.getAsJsonObject().get("call_type").getAsString();

            MethodID.CallType callType = MethodID.CallType.valueOf(callTypeStr.toUpperCase());

            methodID = new MethodID(Type.getObjectType(owner), name, Type.getMethodType(desc), callType);
        }

        if(map != null){
            //Remap the method ID
            methodID = remapMethod(methodID, map);
        }

        return methodID;
    }

    private static MethodID remapMethod(MethodID methodID, @NotNull MappingResolver map) {
        //Map owner
        Type mappedOwner = remapType(methodID.getOwner(), map, false);

        //Map name
        String mappedName = map.mapMethodName("intermediary",
                methodID.getOwner().getClassName(), methodID.getName(), methodID.getDescriptor().getInternalName()
        );

        //Map desc
        Type[] args = methodID.getDescriptor().getArgumentTypes();
        Type returnType = methodID.getDescriptor().getReturnType();

        Type[] mappedArgs = new Type[args.length];
        for(int i = 0; i < args.length; i++){
            mappedArgs[i] = remapType(args[i], map, false);
        }

        Type mappedReturnType = remapType(returnType, map, false);

        Type mappedDesc = Type.getMethodType(mappedReturnType, mappedArgs);

        return new MethodID(mappedOwner, mappedName, mappedDesc, methodID.getCallType());
    }

    private static Type remapType(Type type, @NotNull MappingResolver map, boolean warnIfNotPresent) {
        if(type.getSort() == Type.ARRAY){
            Type componentType = remapType(type.getElementType(), map, warnIfNotPresent);
            return Type.getType("[" + componentType.getDescriptor());
        }else if(type.getSort() == Type.OBJECT) {
            String unmapped = type.getClassName();
            String mapped = map.mapClassName("intermediary", unmapped);
            if (mapped == null) {
                if (warnIfNotPresent) {
                    System.err.println("Could not remap type: " + unmapped);
                }
                return type;
            }
            return Type.getObjectType(mapped.replace('.', '/'));
        }else{
            return type;
        }
    }

    private static MappingResolver getMapper() {
        try {
            return FabricLoader.getInstance().getMappingResolver();
        }catch (NullPointerException e){
            e.printStackTrace();
            System.err.println("Not running fabric! Creating mappings!");
            MappingConfiguration mappingConfig = new MappingConfiguration();
            return new FabricMappingResolver(mappingConfig::getMappings, "named");
        }
    }
}