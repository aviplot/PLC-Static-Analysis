package dev.plca.semantic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A static FB/program instance: {@code MAIN.fbMotor.fbTimer}. Arrays of FBs appear once with {@code [*]}. */
public final class Instance {

    private final String path;
    private final PouSymbol pou;
    private final VariableSymbol declaredBy;
    private final Instance parent;
    private final List<Instance> children = new ArrayList<>();

    Instance(String path, PouSymbol pou, VariableSymbol declaredBy, Instance parent) {
        this.path = path;
        this.pou = pou;
        this.declaredBy = declaredBy;
        this.parent = parent;
    }

    public String path() {
        return path;
    }

    /** The FB/program type of this instance. */
    public PouSymbol pou() {
        return pou;
    }

    /** The variable declaring it; null for programs and other roots. */
    public VariableSymbol declaredBy() {
        return declaredBy;
    }

    public Instance parent() {
        return parent;
    }

    public List<Instance> children() {
        return Collections.unmodifiableList(children);
    }

    void addChild(Instance i) {
        children.add(i);
    }

    @Override
    public String toString() {
        return path + " : " + pou.name();
    }
}
