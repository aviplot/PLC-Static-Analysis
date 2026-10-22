package dev.plca.frontend.l5x;

import dev.plca.frontend.st.kit.XmlInput;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import org.codehaus.stax2.XMLStreamReader2;

/**
 * Reads the structure of an L5X export with StAX (never a DOM: L5X files reach hundreds of MB): data types, AOIs, tags,
 * programs, routines, tasks and module names. Embedded text (rung text and comments, ST lines, string values) is only
 * located, as file offset ranges; {@code XmlText} decodes it later with an exact source map, as in the TwinCAT reader.
 *
 * <p>Names keep the offsets of their attribute value so findings can point at them. Partial exports (a routine, an AOI,
 * a rung) are read the same way: context elements ({@code Use="Context"}) are treated like target elements.
 *
 * <p>Element and attribute names: Logix 5000 Import/Export Reference Manual (1756-RM084); see
 * {@code docs/grammar/rockwell.md} §1.
 */
final class L5xReader {

    /** A file offset range {@code [start, end)}; {@code start < 0} when absent. */
    record Range(int start, int end) {
        static final Range NONE = new Range(-1, -1);

        boolean present() {
            return start >= 0;
        }
    }

    /** An attribute value and the range of its raw text (between the quotes). */
    record Attr(String value, Range range) {
        static final Attr NONE = new Attr(null, Range.NONE);

        boolean present() {
            return value != null;
        }
    }

    static final class Project {
        Attr targetType = Attr.NONE;
        Attr softwareRevision = Attr.NONE;
        Attr controller = Attr.NONE;
        final List<DataTypeDef> dataTypes = new ArrayList<>();
        final List<AoiDef> aois = new ArrayList<>();
        final List<TagDef> tags = new ArrayList<>();
        final List<ProgramDef> programs = new ArrayList<>();
        final List<TaskDef> tasks = new ArrayList<>();
        final List<String> modules = new ArrayList<>();
        /** Routines outside any program or AOI (not seen in real exports; kept so nothing is lost). */
        final List<RoutineDef> looseRoutines = new ArrayList<>();

        String error;
        int errorOffset = -1;
    }

    static final class DataTypeDef {
        final Attr name;
        final Attr family;
        final int offset;
        final List<MemberDef> members = new ArrayList<>();

        DataTypeDef(Attr name, Attr family, int offset) {
            this.name = name;
            this.family = family;
            this.offset = offset;
        }
    }

    /** A UDT member, AOI parameter, AOI local tag or tag: the attributes all of them share. */
    static class Typed {
        final Attr name;
        final Attr dataType;
        /**
         * {@code Dimension} (members) or {@code Dimensions} (tags, parameters): {@code "0"}, {@code "10"}, {@code "2
         * 3"}.
         */
        final Attr dimensions;

        final Attr externalAccess;
        final int offset;
        /** Elementary initial value ({@code DataValue Value} or {@code String} data); null when absent. */
        String initialValue;

        Range initialValueRange = Range.NONE;
        boolean initialIsString;

        Typed(Attr name, Attr dataType, Attr dimensions, Attr externalAccess, int offset) {
            this.name = name;
            this.dataType = dataType;
            this.dimensions = dimensions;
            this.externalAccess = externalAccess;
            this.offset = offset;
        }
    }

    static final class MemberDef extends Typed {
        final boolean hidden;
        final Attr target;

        MemberDef(Attr name, Attr dataType, Attr dimension, Attr externalAccess, boolean hidden, Attr target, int at) {
            super(name, dataType, dimension, externalAccess, at);
            this.hidden = hidden;
            this.target = target;
        }
    }

    /** A controller/program tag, AOI parameter or AOI local tag. */
    static final class TagDef extends Typed {
        /** Base, Alias, Produced, Consumed (tags); Base for parameters. */
        final String tagType;
        /** Tags: program parameter usage (Input/Output/InOut/Public) or null; parameters: Input/Output/InOut. */
        final String usage;

        final Attr aliasFor;
        final boolean constant;
        final boolean required;
        final boolean safety;

        TagDef(
                Attr name,
                Attr dataType,
                Attr dimensions,
                Attr externalAccess,
                String tagType,
                String usage,
                Attr aliasFor,
                boolean constant,
                boolean required,
                boolean safety,
                int offset) {
            super(name, dataType, dimensions, externalAccess, offset);
            this.tagType = tagType;
            this.usage = usage;
            this.aliasFor = aliasFor;
            this.constant = constant;
            this.required = required;
            this.safety = safety;
        }
    }

    static final class AoiDef {
        final Attr name;
        final int offset;
        final boolean encoded;
        final boolean safety;
        final List<TagDef> parameters = new ArrayList<>();
        final List<TagDef> localTags = new ArrayList<>();
        final List<RoutineDef> routines = new ArrayList<>();

        AoiDef(Attr name, int offset, boolean encoded, boolean safety) {
            this.name = name;
            this.offset = offset;
            this.encoded = encoded;
            this.safety = safety;
        }
    }

    static final class ProgramDef {
        final Attr name;
        final Attr mainRoutine;
        final Attr faultRoutine;
        final boolean disabled;
        final boolean safety;
        final int offset;
        final List<TagDef> tags = new ArrayList<>();
        final List<RoutineDef> routines = new ArrayList<>();

        ProgramDef(Attr name, Attr mainRoutine, Attr faultRoutine, boolean disabled, boolean safety, int offset) {
            this.name = name;
            this.mainRoutine = mainRoutine;
            this.faultRoutine = faultRoutine;
            this.disabled = disabled;
            this.safety = safety;
            this.offset = offset;
        }
    }

    static final class RoutineDef {
        final Attr name;
        /** RLL, ST, FBD, SFC, Encrypted (EncodedData routines), or what the file says. */
        final String type;

        final int offset;
        final List<RungDef> rungs = new ArrayList<>();
        final List<LineDef> lines = new ArrayList<>();
        /** {@code <SFCContent>} of an SFC routine, else null. */
        SfcDef sfc;
        /** {@code <FBDContent>} of an FBD routine, else null (M11). */
        FbdDef fbd;

        RoutineDef(Attr name, String type, int offset) {
            this.name = name;
            this.type = type;
            this.offset = offset;
        }
    }

    static final class RungDef {
        final int number;
        final String type;
        final int offset;
        Range text = Range.NONE;
        Range comment = Range.NONE;

        RungDef(int number, String type, int offset) {
            this.number = number;
            this.type = type;
            this.offset = offset;
        }
    }

    record LineDef(int number, Range text) {}

    /**
     * An SFC routine's chart: its elements and {@code <DirectedLink>}s. Legs of a {@code <Branch>} are mapped to their
     * branch. TODO(verify-format): element and attribute names are taken from corpus exports (Studio 5000 V20–V33), not
     * checked against the Import/Export manual (1756-RM084); see {@code docs/grammar/rockwell.md} §SFC.
     */
    /** The elements of an FBD routine, sheet by sheet in file order (M11). */
    static final class FbdDef {
        final List<FbdNode> nodes = new ArrayList<>();
    }

    /**
     * An element of an FBD sheet ({@code IRef}, {@code ORef}, {@code Block}, {@code Function},
     * {@code AddOnInstruction}, {@code ICon}, {@code OCon}, {@code Wire}, {@code FeedbackWire}, {@code JSR}, …) with
     * every attribute; {@code params}: an AOI's {@code InOutParameter Name/Argument} pairs.
     */
    static final class FbdNode {
        final String tag;
        final int sheet;
        final int offset;
        final java.util.Map<String, Attr> attributes = new java.util.LinkedHashMap<>();
        final List<Attr[]> params = new ArrayList<>();

        FbdNode(String tag, int sheet, int offset) {
            this.tag = tag;
            this.sheet = sheet;
            this.offset = offset;
        }

        Attr attr(String name) {
            return attributes.getOrDefault(name, Attr.NONE);
        }

        int intAttr(String name, int fallback) {
            String v = attr(name).value();
            if (v == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(v.strip());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
    }

    static final class SfcDef {
        final List<SfcNode> nodes = new ArrayList<>();
        /** {@code from → to} element IDs, legs already replaced by their branch. */
        final List<int[]> links = new ArrayList<>();

        final java.util.Map<Integer, Integer> legs = new java.util.HashMap<>();
    }

    /**
     * {@code <Step>}, {@code <Transition>}, {@code <Stop>}, {@code <Branch>} or a step's {@code <Action>} (with the
     * step's ID in {@code step}). {@code lines}: an action's body, a transition's condition (ST lines).
     */
    static final class SfcNode {
        final String tag;
        final int id;
        final Attr operand;
        final int x;
        final int y;
        final boolean initial;
        final String qualifier;
        final String branchType;
        final String branchFlow;
        final int step;
        final int offset;
        final List<LineDef> lines = new ArrayList<>();

        SfcNode(
                String tag,
                int id,
                Attr operand,
                int x,
                int y,
                boolean initial,
                String qualifier,
                String branchType,
                String branchFlow,
                int step,
                int offset) {
            this.tag = tag;
            this.id = id;
            this.operand = operand;
            this.x = x;
            this.y = y;
            this.initial = initial;
            this.qualifier = qualifier;
            this.branchType = branchType;
            this.branchFlow = branchFlow;
            this.step = step;
            this.offset = offset;
        }
    }

    static final class TaskDef {
        final Attr name;
        final String type;
        final Attr rate;
        final Attr priority;
        final boolean inhibited;
        final int offset;
        final List<Attr> programs = new ArrayList<>();
        String eventTrigger;

        TaskDef(Attr name, String type, Attr rate, Attr priority, boolean inhibited, int offset) {
            this.name = name;
            this.type = type;
            this.rate = rate;
            this.priority = priority;
            this.inhibited = inhibited;
            this.offset = offset;
        }
    }

    private final String xml;
    private final Project project = new Project();
    private final Deque<String> path = new ArrayDeque<>();
    private XMLStreamReader2 r;

    // innermost open owners; null when not inside one
    private DataTypeDef dataType;
    private AoiDef aoi;
    private ProgramDef program;
    private RoutineDef routine;
    private RungDef rung;
    /** The SFC element whose ST lines are being read (an action's body, a transition's condition). */
    private SfcNode sfcNode;
    /** The number of the FBD sheet being read. */
    private int fbdSheet;

    private SfcNode sfcStep;
    private SfcNode sfcBranch;
    private TaskDef task;
    private Typed valueOwner;
    private int contentStart = -1;
    private String contentOf;
    private int lineNumber;

    private L5xReader(String xml) {
        this.xml = xml;
    }

    static Project read(String xml) {
        L5xReader reader = new L5xReader(xml);
        reader.run();
        return reader.project;
    }

    private void run() {
        try {
            r = (XMLStreamReader2) XmlInput.factory().createXMLStreamReader(new StringReader(xml));
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    start();
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    end();
                }
            }
        } catch (XMLStreamException e) {
            int offset =
                    e.getLocation() == null ? 0 : Math.max(0, e.getLocation().getCharacterOffset());
            project.error = e.getMessage() == null
                    ? "malformed XML"
                    : e.getMessage().lines().findFirst().orElse("malformed XML");
            project.errorOffset = Math.min(offset, xml.length());
        } catch (RuntimeException e) {
            // Woodstox reports some limit violations unchecked; treat them like malformed input
            project.error = String.valueOf(e.getMessage());
            project.errorOffset = 0;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (XMLStreamException ignored) {
                    // nothing to release for a StringReader
                }
            }
        }
    }

    private void start() {
        String tag = r.getLocalName();
        int at = (int) r.getLocationInfo().getStartingCharOffset();
        String parent = path.peek();
        path.push(tag);
        switch (tag) {
            case "RSLogix5000Content" -> {
                project.targetType = attr(at, "TargetType");
                project.softwareRevision = attr(at, "SoftwareRevision");
            }
            case "Controller" -> project.controller = attr(at, "Name");
            case "DataType" -> {
                if ("DataTypes".equals(parent)) {
                    dataType = new DataTypeDef(attr(at, "Name"), attr(at, "Family"), at);
                    project.dataTypes.add(dataType);
                }
            }
            case "Member" -> {
                if (dataType != null && "Members".equals(parent)) {
                    dataType.members.add(new MemberDef(
                            attr(at, "Name"),
                            attr(at, "DataType"),
                            attr(at, "Dimension"),
                            attr(at, "ExternalAccess"),
                            "true".equalsIgnoreCase(value("Hidden")),
                            attr(at, "Target"),
                            at));
                }
            }
            case "AddOnInstructionDefinition" -> {
                aoi = new AoiDef(attr(at, "Name"), at, false, "Safety".equalsIgnoreCase(value("Class")));
                project.aois.add(aoi);
            }
            case "EncodedData" -> encodedData(at);
            case "Parameter" -> {
                if (aoi != null && "Parameters".equals(parent)) {
                    TagDef p = tagDef(at, value("Usage"));
                    aoi.parameters.add(p);
                    valueOwner = p;
                }
            }
            case "LocalTag" -> {
                if (aoi != null && "LocalTags".equals(parent)) {
                    TagDef t = tagDef(at, null);
                    aoi.localTags.add(t);
                    valueOwner = t;
                }
            }
            case "Tag" -> {
                if ("Tags".equals(parent)) {
                    TagDef t = tagDef(at, value("Usage"));
                    if (program != null) {
                        program.tags.add(t);
                    } else if (aoi == null) {
                        project.tags.add(t);
                    }
                    valueOwner = t;
                }
            }
            case "Data", "DefaultData" -> {
                if (valueOwner != null && "String".equals(value("Format"))) {
                    contentStart = XmlInput.endOfStartTag(xml, at);
                    contentOf = "string";
                }
            }
            case "DataValue" -> {
                // only a direct value of the tag (elementary tags), not members of structures or arrays
                if (valueOwner != null
                        && valueOwner.initialValue == null
                        && ("Data".equals(parent) || "DefaultData".equals(parent))) {
                    Attr v = attr(at, "Value");
                    if (v.present()) {
                        valueOwner.initialValue = v.value();
                        valueOwner.initialValueRange = v.range();
                    }
                }
            }
            case "Program" -> {
                if ("Programs".equals(parent)) {
                    program = new ProgramDef(
                            attr(at, "Name"),
                            attr(at, "MainRoutineName"),
                            attr(at, "FaultRoutineName"),
                            "true".equalsIgnoreCase(value("Disabled")),
                            "Safety".equalsIgnoreCase(value("Class")),
                            at);
                    project.programs.add(program);
                }
            }
            case "Routine" -> {
                if ("Routines".equals(parent)) {
                    startRoutine(new RoutineDef(attr(at, "Name"), nonNull(value("Type")), at));
                }
            }
            case "Rung" -> {
                if (routine != null) {
                    rung = new RungDef(intValue("Number", routine.rungs.size()), nonNull(value("Type")), at);
                    routine.rungs.add(rung);
                }
            }
            case "Text" -> {
                if (rung != null && "Rung".equals(parent)) {
                    contentStart = XmlInput.endOfStartTag(xml, at);
                    contentOf = "rungText";
                }
            }
            case "Comment" -> {
                if (rung != null && "Rung".equals(parent)) {
                    contentStart = XmlInput.endOfStartTag(xml, at);
                    contentOf = "rungComment";
                }
            }
            case "SFCContent", "Step", "Transition", "Stop", "Branch", "Action", "Leg", "DirectedLink" ->
                sfcStart(tag, at, parent);
            case "FBDContent", "Sheet", "InOutParameter" -> fbdStart(tag, at, parent);
            case "Line" -> {
                if (routine != null && "STContent".equals(parent)) {
                    lineNumber = intValue("Number", routine.lines.size());
                    contentStart = XmlInput.endOfStartTag(xml, at);
                    contentOf = "line";
                }
            }
            case "Task", "ScheduledProgram", "EventInfo" -> taskStart(tag, at, parent);
            case "Module" -> {
                String name = value("Name");
                if (name != null && "Modules".equals(parent)) {
                    project.modules.add(name);
                }
            }
            default -> {
                if ("Sheet".equals(parent) && routine != null && routine.fbd != null) {
                    fbdStart(tag, at, parent);
                }
            }
        }
    }

    /**
     * {@code <EncodedData EncodedType="AddOnInstructionDefinition" Name=…>}: a source-protected AOI whose parameters
     * are still listed in the clear; {@code EncodedType="Routine"}: a protected routine.
     */
    private void encodedData(int at) {
        String type = value("EncodedType");
        if ("AddOnInstructionDefinition".equals(type)) {
            aoi = new AoiDef(attr(at, "Name"), at, true, "Safety".equalsIgnoreCase(value("Class")));
            project.aois.add(aoi);
        } else if ("Routine".equals(type) && routine == null) {
            startRoutine(new RoutineDef(attr(at, "Name"), "Encrypted", at));
        }
    }

    private void startRoutine(RoutineDef def) {
        routine = def;
        if (aoi != null) {
            aoi.routines.add(def);
        } else if (program != null) {
            program.routines.add(def);
        } else {
            project.looseRoutines.add(def);
        }
    }

    private void end() {
        String tag = path.pop();
        int at = (int) r.getLocationInfo().getStartingCharOffset();
        if (contentOf != null) {
            Range content = new Range(contentStart, Math.max(contentStart, at));
            switch (contentOf) {
                case "rungText" -> {
                    if (tag.equals("Text")) {
                        rung.text = content;
                        contentOf = null;
                    }
                }
                case "rungComment" -> {
                    if (tag.equals("Comment")) {
                        rung.comment = content;
                        contentOf = null;
                    }
                }
                case "line" -> {
                    if (tag.equals("Line")) {
                        (routine.sfc != null && sfcNode != null ? sfcNode.lines : routine.lines)
                                .add(new LineDef(lineNumber, content));
                        contentOf = null;
                    }
                }
                case "string" -> {
                    if ((tag.equals("Data") || tag.equals("DefaultData")) && valueOwner != null) {
                        if (valueOwner.initialValue == null) {
                            valueOwner.initialValue = xml.substring(content.start(), content.end());
                            valueOwner.initialValueRange = content;
                            valueOwner.initialIsString = true;
                        }
                        contentOf = null;
                    }
                }
                default -> contentOf = null;
            }
        }
        switch (tag) {
            case "DataType" -> dataType = null;
            case "AddOnInstructionDefinition" -> aoi = null;
            case "EncodedData" -> {
                if (aoi != null && aoi.encoded) {
                    aoi = null;
                } else if (routine != null && "Encrypted".equals(routine.type)) {
                    routine = null;
                }
            }
            case "Parameter", "LocalTag", "Tag" -> valueOwner = null;
            case "Program" -> {
                if ("Programs".equals(path.peek())) {
                    program = null;
                }
            }
            case "Routine" -> routine = null;
            case "Action" -> sfcNode = sfcStep;
            case "Step", "Transition", "Stop", "Branch" -> {
                sfcNode = null;
                sfcStep = null;
                sfcBranch = null;
            }
            case "Rung" -> rung = null;
            case "Task" -> task = null;
            default -> {}
        }
    }

    /** A task and what it schedules. */
    private void taskStart(String tag, int at, String parent) {
        switch (tag) {
            case "Task" -> {
                if ("Tasks".equals(parent)) {
                    task = new TaskDef(
                            attr(at, "Name"),
                            nonNull(value("Type")),
                            attr(at, "Rate"),
                            attr(at, "Priority"),
                            "true".equalsIgnoreCase(value("InhibitTask")),
                            at);
                    project.tasks.add(task);
                }
            }
            case "ScheduledProgram" -> {
                if (task != null) {
                    task.programs.add(attr(at, "Name"));
                }
            }
            case "EventInfo" -> {
                if (task != null) {
                    task.eventTrigger = value("EventTrigger");
                }
            }
            default -> {}
        }
    }

    /** Elements of an FBD routine's {@code <FBDContent>} (M11): everything on a sheet is kept with its attributes. */
    private void fbdStart(String tag, int at, String parent) {
        if (routine == null) {
            return;
        }
        switch (tag) {
            case "FBDContent" -> {
                routine.fbd = new FbdDef();
                fbdSheet = 0;
            }
            case "Sheet" -> fbdSheet = intValue("Number", fbdSheet + 1);
            case "InOutParameter" -> {
                if (routine.fbd != null && "AddOnInstruction".equals(parent) && !routine.fbd.nodes.isEmpty()) {
                    routine.fbd.nodes.getLast().params.add(new Attr[] {attr(at, "Name"), attr(at, "Argument")});
                }
            }
            default -> {
                if (routine.fbd != null && "Sheet".equals(parent)) {
                    FbdNode n = new FbdNode(tag, fbdSheet, at);
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        String name = r.getAttributeLocalName(i);
                        n.attributes.put(name, attr(at, name));
                    }
                    routine.fbd.nodes.add(n);
                }
            }
        }
    }

    /** Elements of an SFC routine's {@code <SFCContent>} (M10). */
    private void sfcStart(String tag, int at, String parent) {
        switch (tag) {
            case "SFCContent" -> {
                if (routine != null) {
                    routine.sfc = new SfcDef();
                }
            }
            case "Step", "Transition", "Stop", "Branch" -> {
                if (routine != null && routine.sfc != null && "SFCContent".equals(parent)) {
                    SfcNode n = sfcNode(tag, at, -1);
                    routine.sfc.nodes.add(n);
                    sfcNode = n;
                    sfcStep = tag.equals("Step") ? n : null;
                    sfcBranch = tag.equals("Branch") ? n : null;
                }
            }
            case "Action" -> {
                if (routine != null && routine.sfc != null && sfcStep != null && "Step".equals(parent)) {
                    SfcNode n = sfcNode(tag, at, sfcStep.id);
                    routine.sfc.nodes.add(n);
                    sfcNode = n;
                }
            }
            case "Leg" -> {
                if (routine != null && routine.sfc != null && sfcBranch != null) {
                    routine.sfc.legs.put(intValue("ID", -1), sfcBranch.id);
                }
            }
            case "DirectedLink" -> {
                if (routine != null && routine.sfc != null) {
                    routine.sfc.links.add(new int[] {intValue("FromID", -1), intValue("ToID", -1)});
                }
            }
            default -> {}
        }
    }

    private SfcNode sfcNode(String tag, int at, int step) {
        return new SfcNode(
                tag,
                intValue("ID", -1),
                attr(at, "Operand"),
                intValue("X", 0),
                intValue("Y", 0),
                "true".equalsIgnoreCase(value("InitialStep")),
                value("Qualifier"),
                value("BranchType"),
                value("BranchFlow"),
                step,
                at);
    }

    private TagDef tagDef(int at, String usage) {
        String tagType = value("TagType");
        return new TagDef(
                attr(at, "Name"),
                attr(at, "DataType"),
                attr(at, "Dimensions"),
                attr(at, "ExternalAccess"),
                tagType == null ? "Base" : tagType,
                usage,
                attr(at, "AliasFor"),
                "true".equalsIgnoreCase(value("Constant")),
                "true".equalsIgnoreCase(value("Required")),
                "Safety".equalsIgnoreCase(value("Class")),
                at);
    }

    // ---- attributes -----------------------------------------------------------------------------

    private String value(String name) {
        return r.getAttributeValue(null, name);
    }

    private int intValue(String name, int fallback) {
        String v = value(name);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    /** The attribute's value (from StAX, entities resolved) and the range of its raw text in the start tag. */
    private Attr attr(int tagStart, String name) {
        String v = value(name);
        if (v == null) {
            return Attr.NONE;
        }
        return new Attr(v, attributeRange(xml, tagStart, name));
    }

    /** Range of the raw value of attribute {@code name} in the start tag at {@code lt}; NONE if not found. */
    static Range attributeRange(String xml, int lt, String name) {
        int[] r = XmlInput.attributeRange(xml, lt, name);
        return r == null ? Range.NONE : new Range(r[0], r[1]);
    }
}
