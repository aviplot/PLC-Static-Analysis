package dev.plca.rules;

import dev.plca.rules.logic.BoolComparedWithLiteral;
import dev.plca.rules.logic.BoolThroughIf;
import dev.plca.rules.logic.ConditionRule;
import dev.plca.rules.logic.ElseAfterLeave;
import dev.plca.rules.logic.ElseOnlyIf;
import dev.plca.rules.logic.EmptyBranch;
import dev.plca.rules.logic.EmptyThen;
import dev.plca.rules.logic.IdenticalBranches;
import dev.plca.rules.logic.LoopEndsInFirstPass;
import dev.plca.rules.logic.NestedIf;
import dev.plca.rules.logic.NotBeforeComparison;
import dev.plca.rules.logic.OverwrittenBeforeRead;
import dev.plca.rules.logic.SameStatementInBranches;
import dev.plca.rules.logic.SelfAssignment;
import dev.plca.rules.plc.CrossTaskWrite;
import dev.plca.rules.plc.DirectAddressing;
import dev.plca.rules.plc.DoubleCoil;
import dev.plca.rules.plc.JumpUsage;
import dev.plca.rules.plc.MultipleInstanceCalls;
import dev.plca.rules.plc.UncheckedDereference;
import dev.plca.rules.quality.CaseWithoutElse;
import dev.plca.rules.quality.Complexity;
import dev.plca.rules.quality.DivisionByZero;
import dev.plca.rules.quality.FloatEquality;
import dev.plca.rules.quality.ForVariableModified;
import dev.plca.rules.quality.ImplicitNarrowing;
import dev.plca.rules.quality.InputWrite;
import dev.plca.rules.quality.LoopWithoutExit;
import dev.plca.rules.quality.MagicNumber;
import dev.plca.rules.quality.NamingConvention;
import dev.plca.rules.quality.ReadBeforeAssignment;
import dev.plca.rules.quality.Recursion;
import dev.plca.rules.quality.UnreachableCode;
import dev.plca.rules.quality.UnusedPou;
import dev.plca.rules.quality.UnusedVariable;
import dev.plca.rules.security.HardCodedSecret;
import dev.plca.rules.security.HiddenTrigger;
import dev.plca.rules.security.UncheckedExternalInput;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The known rules and their descriptors, in a fixed order (reports list rules in this order). */
public final class RuleRegistry {

    private final Map<String, Rule> rules = new LinkedHashMap<>();
    private final Map<String, RuleDescriptor> descriptors = new LinkedHashMap<>();

    public RuleRegistry(List<Rule> rules) {
        for (Rule r : rules) {
            RuleDescriptor d = DescriptorParser.load(r.id());
            if (!d.id().equals(r.id())) {
                throw new IllegalStateException("descriptor id " + d.id() + " does not match rule " + r.id());
            }
            if (this.rules.putIfAbsent(key(r.id()), r) != null) {
                throw new IllegalStateException("duplicate rule " + r.id());
            }
            descriptors.put(key(r.id()), d);
        }
    }

    /** The built-in rule set. */
    public static RuleRegistry standard() {
        return new RuleRegistry(List.of(
                new UnusedVariable(),
                new CaseWithoutElse(),
                new FloatEquality(),
                new DivisionByZero(),
                new ForVariableModified(),
                new InputWrite(),
                new Recursion(),
                new DoubleCoil(),
                new DirectAddressing(),
                new HardCodedSecret(),
                new UnreachableCode(),
                new LoopWithoutExit(),
                new Complexity(),
                new JumpUsage(),
                new UnusedPou(),
                new MultipleInstanceCalls(),
                new CrossTaskWrite(),
                new ReadBeforeAssignment(),
                new ImplicitNarrowing(),
                new UncheckedDereference(),
                new UncheckedExternalInput(),
                new HiddenTrigger(),
                new MagicNumber(),
                new NamingConvention(),
                new ConditionRule("PLCA-L001"),
                new ConditionRule("PLCA-L002"),
                new ConditionRule("PLCA-L003"),
                new ConditionRule("PLCA-L004"),
                new ConditionRule("PLCA-L005"),
                new ConditionRule("PLCA-L006"),
                new ConditionRule("PLCA-L007"),
                new ConditionRule("PLCA-L008"),
                new ConditionRule("PLCA-L009"),
                new ConditionRule("PLCA-L010"),
                new ConditionRule("PLCA-L011"),
                new ConditionRule("PLCA-L012"),
                new IdenticalBranches(),
                new SelfAssignment(),
                new ElseOnlyIf(),
                new NestedIf(),
                new ElseAfterLeave(),
                new EmptyThen(),
                new BoolThroughIf(),
                new SameStatementInBranches(),
                new EmptyBranch(),
                new LoopEndsInFirstPass(),
                new OverwrittenBeforeRead(),
                new NotBeforeComparison(),
                new BoolComparedWithLiteral()));
    }

    public List<RuleDescriptor> descriptors() {
        return new ArrayList<>(descriptors.values());
    }

    public Optional<RuleDescriptor> descriptor(String id) {
        return Optional.ofNullable(descriptors.get(key(id)));
    }

    public Optional<Rule> rule(String id) {
        return Optional.ofNullable(rules.get(key(id)));
    }

    private static String key(String id) {
        return id.toUpperCase(Locale.ROOT);
    }
}
