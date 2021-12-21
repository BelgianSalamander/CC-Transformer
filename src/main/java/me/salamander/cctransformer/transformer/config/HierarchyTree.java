package me.salamander.cctransformer.transformer.config;

import org.objectweb.asm.Type;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.*;

public class HierarchyTree {
    private Node root;
    private Map<Type, Node> lookup = new HashMap<>();

    public void addNode(Type value, Type parent){
        Node node;
        if(parent == null){
            node = new Node(value, 0);
            if(root != null){
                throw new IllegalStateException("Root has already been assigned");
            }
            root = node;
        }else{
            Node parentNode = lookup.get(parent);
            node = new Node(value, parentNode.depth + 1);
            if(parentNode == null){
                throw new IllegalStateException("Parent node not found");
            }
            parentNode.children.add(node);
            node.parent = parentNode;
        }
        lookup.put(value, node);
    }

    public Iterable<Type> ancestry(Type subType){
        return new AncestorIterable(lookup.get(subType));
    }

    public void print(PrintStream out) {
        this.print(out, root, 0);
    }

    private void print(PrintStream out, Node node, int depth) {
        for(int i = 0; i < depth; i++){
            out.print("  ");
        }
        out.println(node.value);
        for(Node child : node.children){
            print(out, child, depth + 1);
        }
    }

    public Collection<Node> nodes() {
        return lookup.values();
    }

    public Node getNode(Type owner) {
        return lookup.get(owner);
    }

    public void addInterface(Type itf, Type subType) {
        Node node = lookup.get(subType);
        if(node == null){
            throw new IllegalStateException("Node not found");
        }
        node.interfaces.add(itf);
    }

    public void add(Class<?> clazz){
        while(true){
            Type subType = Type.getType(clazz);
            if(lookup.containsKey(subType)){
                break;
            }

            Class<?> parent = clazz.getSuperclass();
            assert parent != null;

            addNode(subType, Type.getType(parent));
            clazz = parent;
        }
    }

    public static class Node{
        private final Type value;
        private final Set<Node> children = new HashSet<>();
        private final List<Type> interfaces = new ArrayList<>(4);
        private Node parent = null;
        private final int depth;

        public Node(Type value, int depth){
            this.value = value;
            this.depth = depth;
        }

        public Type getValue() {
            return value;
        }

        public Set<Node> getChildren() {
            return children;
        }

        public Node getParent() {
            return parent;
        }

        public int getDepth() {
            return depth;
        }

        public void addInterface(Type subType){
            interfaces.add(subType);
        }
    }

    private static class AncestorIterable implements Iterable<Type> {
        private final Node node;

        public AncestorIterable(Node root) {
            node = root;
        }

        @Override
        public Iterator<Type> iterator() {
            return new AncestorIterator(node);
        }

        private class AncestorIterator implements Iterator<Type> {
            private Node current = node;
            private int interfaceIndex = -1;

            public AncestorIterator(Node node) {
                this.current = node;
            }

            @Override
            public boolean hasNext() {
                return current != null;
            }

            /*@Override
            public Type next() {
                Type next = current.value;
                current = current.parent;
                return next;
            }*/

            @Override
            public Type next(){
                Type ret;
                if(interfaceIndex == -1){
                    ret = current.value;
                }else{
                    ret = current.interfaces.get(interfaceIndex);
                }

                interfaceIndex++;
                if(interfaceIndex >= current.interfaces.size()){
                    current = current.parent;
                    interfaceIndex = -1;
                }

                return ret;
            }
        }
    }
}
