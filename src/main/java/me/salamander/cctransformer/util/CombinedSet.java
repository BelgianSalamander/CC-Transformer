package me.salamander.cctransformer.util;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * A set that combines sets. All operations affect all sets.
 * Some operations can be quite expensive. This is due to the fact that you are allowed to modify the underlying sets.
 * @param <T> The subType of the elements in the set
 */
public class CombinedSet<T> implements Set<T> {
    private final Set<T>[] sets;

    public CombinedSet(Set<T>... sets) {
        this.sets = sets;
    }


    @Override
    public int size() {
        return generateCombinedSet().size();
    }

    @Override
    public boolean isEmpty() {
        for(Set<T> set : sets) {
            if(!set.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean contains(Object o) {
        for(Set<T> set : sets) {
            if(set.contains(o)) {
                return true;
            }
        }
        return false;
    }

    @NotNull
    @Override
    public Iterator<T> iterator() {
        return generateCombinedSet().iterator();
    }

    @NotNull
    @Override
    public Object[] toArray() {
        return generateCombinedSet().toArray();
    }

    @NotNull
    @Override
    public <T1> T1[] toArray(@NotNull T1[] a) {
        return generateCombinedSet().toArray(a);
    }

    @Override
    public boolean add(T t) {
        boolean added = false;
        for(Set<T> set : sets) {
            if(set.add(t)){
                added = true;
            }
        }
        return added;
    }

    @Override
    public boolean remove(Object o) {
        boolean removed = false;
        for(Set<T> set : sets) {
            if(set.remove(o)){
                removed = true;
            }
        }
        return removed;
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> c) {
        return generateCombinedSet().containsAll(c);
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends T> c) {
        boolean added = false;
        for(Set<T> set : sets) {
            if(set.addAll(c)){
                added = true;
            }
        }
        return added;
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> c) {
        boolean changed = false;
        for(Set<T> set : sets) {
            if(set.retainAll(c)){
                changed = true;
            }
        }
        return changed;
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> c) {
        boolean removed = false;
        for(Set<T> set : sets) {
            if(set.removeAll(c)){
                removed = true;
            }
        }
        return removed;
    }

    @Override
    public void clear() {
        for(Set<T> set : sets) {
            set.clear();
        }
    }

    private Set<T> generateCombinedSet(){
        Set<T> values = new HashSet<>(sets[0]);
        for (int i = 1; i < sets.length; i++) {
            values.addAll(sets[i]);
        }
        return values;
    }

    @Override
    public boolean equals(Object obj) {
        return generateCombinedSet().equals(obj);
    }

    @Override
    public int hashCode() {
        return generateCombinedSet().hashCode();
    }
}
