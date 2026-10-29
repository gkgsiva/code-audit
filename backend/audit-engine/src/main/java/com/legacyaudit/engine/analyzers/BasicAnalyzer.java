package com.legacyaudit.engine.analyzers;

import static com.legacyaudit.engine.analyzers.BuildAnalyzer.tech;
import static com.legacyaudit.engine.analyzers.DynSql.choice;
import static com.legacyaudit.engine.analyzers.DynSql.concat;
import static com.legacyaudit.engine.analyzers.DynSql.empty;
import static com.legacyaudit.engine.analyzers.DynSql.hole;
import static com.legacyaudit.engine.analyzers.DynSql.lit;
import static com.legacyaudit.engine.analyzers.Integrations.integration;

import com.legacyaudit.engine.analyzers.DynSql.Built;
import com.legacyaudit.engine.analyzers.DynSql.Tracker;
import com.legacyaudit.engine.inventory.Rx;
import com.legacyaudit.model.facts.Category;
import com.legacyaudit.model.facts.Doc;
import com.legacyaudit.model.facts.Evidence;
import com.legacyaudit.model.facts.Fact;
import com.legacyaudit.model.facts.FileRecord;
import com.legacyaudit.model.facts.Schemas;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Visual Basic 6 and VB.NET, read line by line (no parser: decision 135). Both dialects share
 * the statement syntax this analyzer needs, so one reader serves both.
 *
 * <ul>
 *   <li><b>files</b>: VB6 forms, modules, classes, user controls, user documents and property
 *       pages, VB6 projects ({@code .vbp}, {@code .vbg}) and VB.NET source;
 *   <li><b>screens</b>: each VB6 form (its controls from the form header, input controls counted
 *       as fields), user controls and property pages as partials; VB.NET Windows Forms and user
 *       controls; {@code frmX.Show}, {@code Load frmX}, {@code New FormX} as navigation;
 *   <li><b>database access</b>: ADO, DAO, RDO and ADO.NET calls, Dapper and Entity Framework raw
 *       SQL, data controls' {@code RecordSource}; SQL built in code is rebuilt like the other
 *       languages; stored procedures; connection strings (credentials masked);
 *   <li><b>services and entry points</b>: VB.NET controllers, ASMX web methods and WCF
 *       operations; {@code Sub Main}, Windows services, VB6 projects;
 *   <li><b>dependencies</b>: a VB6 project's COM references and ActiveX controls as libraries
 *       ({@code source="com"}); every ActiveX control is a finding;
 *   <li><b>integrations</b>: URLs, connection strings, MSMQ and IBM MQ queues, Winsock hosts,
 *       files, SMTP servers;
 *   <li><b>tech stack</b>: Visual Basic 6 / VB.NET and what {@code Imports} show.
 * </ul>
 *
 * Complexity units ({@code Sub}, {@code Function}, {@code Property}) are measured by the metrics
 * with the same line reader ({@link #logicalLines}).
 */
public final class BasicAnalyzer implements Analyzer {

    public static final String VB6 = "vb6";
    public static final String VBNET = "vbnet";

    // ---- lines ------------------------------------------------------------------------------

    // "[^"]++" keeps Java's regex engine from recursing once per character of a long string.
    static final Pattern STRING = Rx.of("\"(?:[^\"]++|\"\")*\"c?", Rx.I);
    private static final Pattern CONTINUES = Rx.of("(\\s_|[&+,(=]|\\bAnd|\\bOr|\\bAndAlso|\\bOrElse)$", Rx.I);

    static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /**
     * The code of one physical line: a {@code '} outside a string, or a leading {@code REM},
     * starts a comment.
     */
    public static String stripComment(String line) {
        String s = TextUtil.lstrip(line);
        String head = lower(Rx.head(s, 4));
        if (head.equals("rem ") || lower(s).equals("rem")) {
            return "";
        }
        boolean inStr = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                inStr = !inStr;
            } else if (ch == '\'' && !inStr) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** Comments blanked, line breaks kept (for duplication windows). */
    public static String stripCommentsBasic(String text) {
        List<String> out = new ArrayList<>();
        for (String ln : text.split("\n", -1)) {
            out.add(stripComment(ln));
        }
        return String.join("\n", out);
    }

    /** (first line, code) of one statement line. */
    public record Logical(int line, String code) {}

    /**
     * Statement lines: comments removed, {@code _} continuations and VB.NET implicit continuations
     * joined. {@code start} skips the first lines (a VB6 form header).
     */
    public static List<Logical> logicalLines(String text, int start) {
        List<Logical> out = new ArrayList<>();
        Logical pending = null;
        String[] all = text.split("\n", -1);
        for (int k = start; k < all.length; k++) {
            int i = k + 1;
            String code = TextUtil.rstrip(TextUtil.rstrip(stripComment(all[k])), "\r");
            int first;
            if (pending != null) {
                first = pending.line();
                code = pending.code() + " " + TextUtil.strip(code);
                pending = null;
            } else {
                first = i;
            }
            if (TextUtil.strip(code).isEmpty()) {
                continue;
            }
            String stripped = TextUtil.rstrip(code);
            if (CONTINUES.matcher(stripped).find() && !TextUtil.lstrip(stripped).startsWith("#")) {
                pending = new Logical(first, stripped.endsWith("_") ? TextUtil.rstrip(stripped.substring(0, stripped.length() - 1)) : stripped);
                continue;
            }
            out.add(new Logical(first, TextUtil.strip(code)));
        }
        if (pending != null) {
            out.add(new Logical(pending.line(), TextUtil.strip(pending.code())));
        }
        return out;
    }

    /** A piece of text and the separator before it. */
    record Piece(String sep, String text) {}

    /** Splits {@code text} at the characters of {@code seps} outside strings and brackets. */
    static List<Piece> splitTop(String text, String seps) {
        List<Piece> out = new ArrayList<>();
        int depth = 0;
        boolean inStr = false;
        int start = 0;
        String sep = "";
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                inStr = !inStr;
            } else if (inStr) {
                continue;
            } else if ("([{".indexOf(ch) >= 0) {
                depth++;
            } else if (")]}".indexOf(ch) >= 0) {
                depth--;
            } else if (depth == 0 && seps.indexOf(ch) >= 0) {
                if (ch == '=') {
                    char prev = i > 0 ? text.charAt(i - 1) : 0;
                    char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
                    if ((i > 0 && "<>:&+".indexOf(prev) >= 0) || next == '=') {
                        continue;
                    }
                }
                out.add(new Piece(sep, text.substring(start, i)));
                sep = String.valueOf(ch);
                start = i + 1;
            }
        }
        out.add(new Piece(sep, text.substring(start)));
        return out;
    }

    /** Content of the bracket opening at {@code text[start]} and the index after its close. */
    record Balanced(String content, int end) {}

    static Balanced balanced(String text, int start) {
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                inStr = !inStr;
            } else if (inStr) {
                continue;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    return new Balanced(text.substring(start + 1, i), i + 1);
                }
            }
        }
        return null;
    }

    static String unquote(String litText) {
        String body = lower(litText).endsWith("\"c") ? litText.substring(0, litText.length() - 1) : litText;
        if (body.length() < 2) {
            return "";
        }
        return body.substring(1, body.length() - 1).replace("\"\"", "\"");
    }

    /** Arguments of a call: {@code (a, b)} or the VB6 statement form {@code a, b}. */
    static List<String> args(String text) {
        String t = TextUtil.strip(text);
        if (t.startsWith("(")) {
            Balanced b = balanced(t, 0);
            if (b != null && TextUtil.strip(t.substring(b.end())).isEmpty()) {
                t = b.content();
            }
        }
        if (t.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Piece p : splitTop(t, ",")) {
            out.add(TextUtil.strip(p.text()));
        }
        return out;
    }

    // ---- statements -------------------------------------------------------------------------

    private static final String MODIFIERS = "(?:(?:Public|Private|Friend|Protected|Shared|Static|Overrides|Overridable|Overloads|"
            + "Shadows|NotOverridable|MustOverride|MustInherit|NotInheritable|Async|Iterator|Partial|"
            + "ReadOnly|WriteOnly|Default|Widening|Narrowing|Global)\\s+)*";
    /** A procedure start (also used by the metrics units). */
    public static final Pattern PROC = Rx.of("^" + MODIFIERS + "(Sub|Function|Property|Operator)\\s+(?:(Get|Let|Set)\\s+)?\\[?(\\w+)\\]?", Rx.I);
    /** A procedure end (also used by the metrics units). */
    public static final Pattern PROC_END = Rx.of("^End\\s+(Sub|Function|Property|Operator)\\b", Rx.I);
    private static final Pattern LAMBDA_OPEN = Rx.of("\\b(?:Async\\s+)?(Sub|Function)\\s*\\([^()]*\\)\\s*(As\\s+[\\w.]+\\s*)?$", Rx.I);
    private static final Pattern TYPE = Rx.of("^" + MODIFIERS + "(Class|Module|Structure|Interface|Namespace)\\s+([\\w.]+)", Rx.I);
    private static final Pattern TYPE_END = Rx.of("^End\\s+(Class|Module|Structure|Interface|Namespace)\\b", Rx.I);
    private static final Pattern DECL = Rx.of("^(?:(?:Dim|Private|Public|Protected|Friend|Static|Shared|Const|ReadOnly|"
            + "Global|WithEvents)\\s+)+(?!Sub\\b|Function\\b|Property\\b|Declare\\b|Event\\b|"
            + "Enum\\b|Type\\b|Class\\b|Structure\\b|Module\\b|Interface\\b|Delegate\\b|Operator\\b)(.+)$", Rx.I);
    private static final Pattern ASSIGN = Rx.of("^(?:(?:Let|Set)\\s+)?([.\\w]+(?:\\([^()]*\\))?(?:\\.\\w+)*)\\s*(&=|\\+=|=)\\s*(.+)$", Rx.I);
    private static final Pattern ATTRIBUTE = Rx.of("^<\\s*([^<>]+?)\\s*>\\s*(.*)$");
    private static final Pattern IF_LINE = Rx.of("^(?:Else)?If\\b(.*?)\\bThen\\b\\s*(.*)$", Rx.I);
    private static final Pattern ELSE = Rx.of("\\bElse\\b", Rx.I);
    private static final Pattern DB_METHOD = Rx.of("\\b([\\w.]+?)\\.(Execute|Open|OpenRecordset|CreateQueryDef|ExecuteReader|ExecuteNonQuery|"
            + "ExecuteScalar|ExecuteXmlReader|ExecuteReaderAsync|ExecuteNonQueryAsync|ExecuteScalarAsync|"
            + "Query|QueryFirst|QueryFirstOrDefault|QuerySingle|QuerySingleOrDefault|QueryMultiple|"
            + "QueryAsync|QueryFirstOrDefaultAsync|ExecuteAsync|SqlQuery|ExecuteSqlCommand|"
            + "ExecuteSqlRaw|ExecuteSqlRawAsync|FromSqlRaw|ExecuteSqlInterpolated|FromSqlInterpolated|"
            + "OpenResultset)\\b\\s*(?:\\(\\s*Of\\s+[\\w.]+\\s*\\))?", Rx.I);
    private static final Pattern NEW_COMMAND = Rx.of("\\bNew\\s+(?:[\\w.]+\\.)?(Sql|OleDb|Odbc|Oracle|MySql|Npgsql|DB2|iDB2|SQLite|Sqlite|Ase)"
            + "(Command|DataAdapter)\\s*\\(", Rx.I);
    private static final Pattern NEW_BUILDER = Rx.of("^(?:New\\s+)?(?:System\\.Text\\.)?StringBuilder\\b\\s*(\\(.*\\))?$", Rx.I);
    private static final Pattern BUILDER_CALL = Rx.of("\\.(Append|AppendLine|AppendFormat|Insert)\\s*\\(", Rx.I);
    private static final Pattern NEWLINES = Rx.of("^(vbCrLf|vbNewLine|vbCr|vbLf|vbTab|Environment\\.NewLine|ControlChars\\.(CrLf|NewLine|Cr|Lf|"
            + "Tab)|Constants\\.vbCrLf)$", Rx.I);
    private static final Pattern CHR = Rx.of("^ChrW?\\$?\\s*\\(\\s*(\\d+)\\s*\\)$", Rx.I);
    private static final Pattern FORMAT_HOLE = Rx.of("\\{(\\d+)(?:[,:][^}]*)?\\}");
    private static final Pattern NAME = Rx.of("^(?:Me\\.)?([A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*)$");
    private static final Pattern SHOW = Rx.of("\\b([A-Za-z_]\\w*)\\.Show(?:Dialog)?\\b|^Load\\s+([A-Za-z_]\\w*)\\s*$|"
            + "\\bNew\\s+([A-Za-z_]\\w*)\\s*(?:\\(\\s*\\))?\\s*(?:\\.Show)", Rx.I);
    private static final Pattern NEW_CLASS = Rx.of("\\bNew\\s+([A-Za-z_][\\w.]*)", Rx.I);
    private static final Pattern SP_MARK = Rx.of("adCmdStoredProc|CommandType\\.StoredProcedure|\\b4\\s*$", Rx.I);
    private static final Pattern PROC_NAME = Rx.of("^[A-Za-z_][\\w$#]*(\\.[A-Za-z_][\\w$#]*){0,2}$");
    private static final Pattern URL = Rx.of("\\b(?:https?|ftps?|sftp|ldaps?|smtp)://[^\\s\"'<>()\\\\]+", Rx.I);
    private static final Pattern OPEN_FILE = Rx.of("^Open\\s+(.+?)\\s+For\\s+(Input|Output|Append|Binary|Random)\\b", Rx.I);
    private static final Pattern DOTNET_FILE = Rx.of("\\b(?:(File)\\.(ReadAllText|ReadAllLines|ReadAllBytes|ReadLines|OpenRead|OpenText|"
            + "WriteAllText|WriteAllLines|WriteAllBytes|AppendAllText|OpenWrite|Create|AppendText)|"
            + "New\\s+(?:System\\.IO\\.)?(StreamReader|StreamWriter|FileStream))\\s*\\(", Rx.I);
    private static final Pattern MSMQ = Rx.of("\\bNew\\s+(?:System\\.Messaging\\.)?MessageQueue\\s*\\(|\\.PathName\\s*=|\\.FormatName\\s*=", Rx.I);
    private static final Pattern MQ_ACCESS = Rx.of("\\.AccessQueue\\s*\\(?", Rx.I);
    private static final Pattern WINSOCK = Rx.of("\\.(?:RemoteHost\\s*=|Connect\\s*\\(?)", Rx.I);
    private static final Pattern SMTP = Rx.of("\\bNew\\s+(?:System\\.Net\\.Mail\\.)?SmtpClient\\s*\\(|\\.Host\\s*=|smtpserver", Rx.I);
    private static final Pattern INPUT_CONTROLS = Rx.of("^(TextBox|ComboBox|CheckBox|OptionButton|RadioButton|ListBox|CheckedListBox|MaskEdBox|"
            + "MaskedTextBox|DTPicker|DateTimePicker|MonthView|MonthCalendar|RichTextBox|DataCombo|"
            + "DataList|DBCombo|DBList|UpDown|NumericUpDown|Slider|TrackBar|ImageCombo|DataGrid|"
            + "DataGridView|MSFlexGrid|MSHFlexGrid|DBGrid|TDBGrid|SSDBGrid|vsFlexGrid|fpSpread|"
            + "ListView|FileListBox|DriveListBox|DirListBox)$", Rx.I);
    private static final List<String> VB6_EXTENSIONS = List.of(".frm", ".ctl", ".dob", ".pag", ".dsr", ".cls", ".bas");
    private static final List<String> VB6_FORMS = List.of(".frm", ".ctl", ".dob", ".pag", ".dsr");
    /** Base classes ({@code Inherits}), then what the class is. */
    private static final Map<String, String> BASES = Map.of("form", "form", "usercontrol", "usercontrol", "servicebase", "windows-service",
            "backgroundservice", "background-service", "controller", "mvc", "apicontroller", "api", "controllerbase", "api", "webservice", "asmx",
            "dbcontext", "dbcontext", "page", "aspx");
    private static final Pattern HTTP_ATTR = Rx.of("^Http(Get|Post|Put|Delete|Patch)$", Rx.I);
    private static final Set<String> COND_BLOCKS = Set.of("if", "select", "for", "do", "while", "catch");
    private static final Pattern VB_NAME = Rx.of("^Attribute\\s+VB_Name\\s*=\\s*\"([^\"]+)\"", Rx.M | Rx.I);
    private static final Pattern IMPORTS_OR_OPTION = Rx.of("^(Imports|Option)\\b", Rx.I);
    private static final Pattern INHERITS = Rx.of("^Inherits\\s+([\\w.]+)", Rx.I);
    private static final Pattern MUST_OVERRIDE = Rx.of("\\bMustOverride\\b|\\bDeclare\\b", Rx.I);
    private static final Pattern ACCESSOR = Rx.of("^(?:(?:Private|Protected|Friend|Public)\\s+)?(Get|Set)\\b", Rx.I);
    private static final Pattern SHARED = Rx.of("\\bShared\\b", Rx.I);
    private static final Pattern PUBLIC = Rx.of("\\bPublic\\b", Rx.I);
    private static final Pattern SKIP_STATEMENT = Rx.of("^(exit|end|on\\s+error|resume|goto|return)\\b");
    private static final Pattern DECLARATOR = Rx.of(
            "^(\\w+)\\s*(?:\\(\\s*\\))?\\s*(?:As\\s+(New\\s+)?([\\w.]+)\\s*(\\(.*\\))?)?\\s*(?:=\\s*(.+))?$", Rx.I);
    private static final Pattern BUILDER_START = Rx.of("^(?:Me\\.)?(\\w+)\\s*\\.");
    private static final Pattern WORD = Rx.of("(?<![.\\w])([A-Za-z_]\\w*)\\b(?!\\s*=[^=])");
    private static final Pattern TO_STRING = Rx.of("^(?:Me\\.)?(\\w+)\\.ToString\\s*\\(\\s*\\)$", Rx.I);
    private static final Pattern FORMAT_CALL = Rx.of("^(?:String\\.Format|Format\\$?)\\s*\\(", Rx.I);
    private static final Pattern IIF = Rx.of("^I?If\\s*\\(", Rx.I);
    private static final Pattern CONCAT = Rx.of("^String\\.Concat\\s*\\(", Rx.I);
    private static final Pattern SETS_CONNECTION = Rx.of("\\.(connectionstring|connect)\\s*=", Rx.I);
    private static final Pattern HOST_NAME = Rx.of("^[\\w.-]+$");
    private static final Pattern MAIL_HOST = Rx.of("^[\\w.-]+\\.\\w+$");
    private static final Pattern SOCKET_HINT = Rx.of("winsock|sck|socket|tcp", Rx.I);
    private static final Pattern MAIL_HINT = Rx.of("smtp|mail", Rx.I);
    private static final Pattern IMPORTS = Rx.of("^Imports\\s+(?:\\w+\\s*=\\s*)?([\\w.]+)", Rx.I);
    private static final Pattern FORM_BEGIN = Rx.of("^Begin\\s+([\\w.]+)\\s+(\\w+)");
    private static final Pattern FORM_PROP = Rx.of("^(\\w+)\\s*=\\s*(.*)$");
    private static final Pattern DBSET_PROPERTY = Rx.of("\\bProperty\\s+(\\w+)\\s+As\\s+DbSet\\s*\\(\\s*Of\\s+([\\w.]+)\\s*\\)", Rx.I);
    private static final Pattern DESKTOP_MAIN = Rx.of("Application\\.Run\\b|\\.Show(Dialog)?\\b|\\bfrm\\w+\\.Show", Rx.I);
    private static final Pattern MEANING_START = Rx.of("^(Public\\s|Friend\\s|Function|Sub)", Rx.I);
    private static final Pattern ATTR_NAME = Rx.of("^([\\w.]+)");
    private static final Pattern ROUTE = Rx.of("^(?:[\\w.]+\\.)?(\\w+?)(?:Attribute)?\\s*\\(\\s*\"([^\"]*)\"");
    private static final Pattern DESIGNER_NEW = Rx.of("=\\s*New\\s+(?:System\\.Windows\\.Forms\\.)?(\\w+)\\s*\\(");
    private static final Pattern VERSION_NUMBER = Rx.of("^\\d+(\\.\\d+)*$");
    private static final Pattern SOAP_HINT = Rx.of("MSSOAPLib|SoapClient|\\?wsdl|\\.asmx", Rx.I);

    /** Index of the first code line of a VB6 form/class/control file (after its header), or 0. */
    static int vb6HeaderEnd(List<String> lines) {
        int last = -1;
        for (int i = 0; i < Math.min(4000, lines.size()); i++) {
            String s = TextUtil.strip(lines.get(i));
            String l = lower(s);
            if (l.startsWith("attribute vb_")) {
                last = i;
            } else if (last >= 0 && !s.isEmpty() && !l.startsWith("attribute ")) {
                break;
            }
        }
        return last >= 0 ? last + 1 : 0;
    }

    /** A control (or the form itself) in a VB6 designer header, with its properties. */
    static final class Control {
        final String type;
        final String name;
        final int line;
        final Map<String, Map.Entry<String, Integer>> props = new HashMap<>();

        Control(String type, String name, int line) {
            this.type = type;
            this.name = name;
            this.line = line;
        }
    }

    record FormHeader(Control root, List<Control> controls) {}

    /** The form (or user control) and its controls from a VB6 designer header. */
    static FormHeader parseFormHeader(List<String> lines) {
        List<Control> stack = new ArrayList<>();
        List<Control> controls = new ArrayList<>();
        Control root = null;
        int inProperty = 0;
        for (int k = 0; k < lines.size(); k++) {
            int i = k + 1;
            String s = TextUtil.strip(lines.get(k));
            if (lower(s).startsWith("attribute vb_")) {
                break;
            }
            if (s.startsWith("BeginProperty")) {
                inProperty++;
                continue;
            }
            if (s.startsWith("EndProperty")) {
                inProperty = Math.max(inProperty - 1, 0);
                continue;
            }
            if (inProperty > 0) {
                continue;
            }
            Matcher m = FORM_BEGIN.matcher(s);
            if (m.lookingAt()) {
                Control c = new Control(m.group(1), m.group(2), i);
                if (root == null) {
                    root = c;
                } else {
                    controls.add(c);
                }
                stack.add(c);
                continue;
            }
            if (s.equals("End") && !stack.isEmpty()) {
                stack.removeLast();
                continue;
            }
            Matcher kv = FORM_PROP.matcher(s);
            if (kv.lookingAt() && !stack.isEmpty()) {
                String value = TextUtil.strip(kv.group(2));
                if (value.startsWith("\"")) {
                    Matcher m2 = STRING.matcher(value);
                    value = m2.lookingAt() ? unquote(m2.group()) : TextUtil.strip(value, "\"");
                }
                stack.getLast().props.put(lower(kv.group(1)), Map.entry(value, i));
            }
        }
        return new FormHeader(root, controls);
    }

    @Override
    public String name() {
        return "basic";
    }

    @Override
    public String version() {
        return "2"; // 2: framework and web service on .NET services
    }

    @Override
    public boolean supports(FileRecord file) {
        return (file.language.equals(VB6) || file.language.equals(VBNET) || file.language.equals("vb6-project"))
                && file.category != Category.TEST && file.category != Category.GENERATED && file.category != Category.BINARY;
    }

    @Override
    public Facts analyze(FileRecord file, AnalyzerContext ctx) {
        Facts facts = new Facts();
        String text = ctx.textOrEmpty();
        facts.coverage.lines = TextUtil.count(text, '\n') + 1;
        if (file.language.equals("vb6-project")) {
            vbp(file.path, text, facts);
        } else {
            new Reader(file, ctx, facts, text).run();
        }
        return facts;
    }

    // ---- VB6 projects -----------------------------------------------------------------------

    private record ComTech(Pattern rx, String name, String category) {}

    /** COM references and ActiveX controls, then (technology, category). */
    private static final List<ComTech> COM_TECH = List.of(
            new ComTech(Rx.of("ActiveX Data Objects|msado\\d*\\.(dll|tlb)", Rx.I), "ADO", "library"),
            new ComTech(Rx.of("DAO \\d|dao\\d+\\.dll|Data Access Objects", Rx.I), "DAO", "library"),
            new ComTech(Rx.of("Remote Data Object|msrdo20", Rx.I), "RDO", "library"),
            new ComTech(Rx.of("Microsoft XML|msxml\\d*\\.dll", Rx.I), "MSXML", "library"),
            new ComTech(Rx.of("SOAP Type Library|mssoap\\d*\\.dll", Rx.I), "Microsoft SOAP Toolkit", "library"),
            new ComTech(Rx.of("Crystal|crystl32\\.ocx|craxdrt\\.dll|crviewer\\.dll", Rx.I), "Crystal Reports", "library"),
            new ComTech(Rx.of("Excel \\d|Excel\\.exe", Rx.I), "Microsoft Office automation (COM)", "library"),
            new ComTech(Rx.of("Word \\d+\\.\\d+ Object|msword", Rx.I), "Microsoft Office automation (COM)", "library"),
            new ComTech(Rx.of("Outlook|msoutl", Rx.I), "Microsoft Office automation (COM)", "library"),
            new ComTech(Rx.of("Scripting Runtime|scrrun\\.dll", Rx.I), "Windows Scripting Runtime", "library"),
            new ComTech(Rx.of("MQAX200|IBM MQSeries Automation|mqax", Rx.I), "IBM MQ client", "middleware"),
            new ComTech(Rx.of("Message Queue|mqoa\\.dll", Rx.I), "MSMQ", "middleware"),
            new ComTech(Rx.of("MSWINSCK\\.OCX|Winsock", Rx.I), "Winsock control", "library"),
            new ComTech(Rx.of("MSINET\\.OCX|Internet Transfer", Rx.I), "Internet Transfer control", "library"),
            new ComTech(Rx.of("MSCOMM32\\.OCX", Rx.I), "MSComm serial control", "library"),
            new ComTech(Rx.of("COMCTL32\\.OCX|MSCOMCTL\\.OCX|MSCOMCT2\\.OCX|Common Controls", Rx.I), "Windows Common Controls (OCX)", "library"),
            new ComTech(Rx.of("MSFLXGRD\\.OCX|MSHFLXGD\\.OCX|FlexGrid", Rx.I), "MSFlexGrid", "library"),
            new ComTech(Rx.of("MSDATGRD\\.OCX|MSADODC\\.OCX|MSDATLST\\.OCX|DBGRID32\\.OCX", Rx.I), "Data-bound controls (OCX)", "library"),
            new ComTech(Rx.of("TABCTL32\\.OCX|COMDLG32\\.OCX|RICHTX32\\.OCX|MSMASK32\\.OCX", Rx.I), "Visual Basic controls (OCX)", "library"),
            new ComTech(Rx.of("ThreeD|THREED32\\.OCX|Sheridan|SSDW3B32|TrueDB|TDBG|vsflex|Spread|fpspr", Rx.I), "Third-party ActiveX controls",
                    "library"));
    private static final Pattern STDOLE = Rx.of("stdole2?\\.tlb|OLE Automation", Rx.I);

    /** A VB6 project ({@code .vbp}) or project group ({@code .vbg}). */
    private static void vbp(String path, String text, Facts facts) {
        List<String> lines = TextUtil.splitLines(text);
        Map<String, Map.Entry<String, Integer>> kv = new HashMap<>();
        int forms = 0;
        for (int k = 0; k < lines.size(); k++) {
            int i = k + 1;
            String raw = lines.get(k);
            if (!raw.contains("=")) {
                continue;
            }
            String key = TextUtil.strip(TextUtil.beforeFirst(raw, "="));
            String value = TextUtil.strip(TextUtil.afterFirst(raw, "="));
            String lk = lower(key);
            if (Set.of("form", "usercontrol", "userdocument", "propertypage").contains(lk)) {
                forms += lk.equals("form") ? 1 : 0;
            } else if (lk.equals("reference")) {
                // *\G{guid}#2.8#0#..\path\msado15.dll#Microsoft ActiveX Data Objects 2.8 Library
                String[] parts = value.split("#", -1);
                if (parts.length >= 5 && !STDOLE.matcher(value).find()) {
                    String desc = TextUtil.strip(parts[4]);
                    if (desc.isEmpty()) {
                        desc = TextUtil.afterLast(parts[3].replace("\\", "/"), "/");
                    }
                    com(facts, path, i, desc, parts[1], "com-reference", parts[3]);
                }
            } else if (lk.equals("object")) {
                // {831FDD16-0C5C-11D2-A9FC-0000F8754DA1}#2.0#0; MSCOMCTL.OCX
                String ver = value.contains("#") ? value.split("#", -1)[1] : null;
                String ocx = value.contains(";") ? TextUtil.strip(TextUtil.afterLast(value, ";")) : value;
                com(facts, path, i, ocx, ver, "activex-control", ocx);
                facts.add("findings", Doc.of(Schemas.FINDING,
                        "id", Fact.makeId("finding", "vb6-activex-control", path, ocx),
                        "rule_id", "vb6-activex-control",
                        "severity", "medium",
                        "category", "portability",
                        "file", path,
                        "line", (long) i,
                        "message", "ActiveX control " + ocx + " is used by the forms; it has no web equivalent and must be replaced in the new "
                                + "user interface",
                        "remediation", "List the screens using it and pick an Angular component (grid, date picker, tree, chart) to replace it.",
                        "evidence", new ArrayList<>(List.of(new Evidence(path, i, Rx.head(TextUtil.strip(raw), 120))))));
            }
            kv.putIfAbsent(lk, Map.entry(TextUtil.strip(value, "\""), i));
        }
        facts.add("tech_stack", tech("Visual Basic 6", "6.0", "language", path, 1));
        if (lower(path).endsWith(".vbg")) {
            return;
        }
        Map.Entry<String, Integer> ptype = kv.getOrDefault("type", Map.entry("Exe", 1));
        String name = kv.containsKey("name") ? kv.get("name").getKey() : TextUtil.beforeLast(TextUtil.afterLast(path, "/"), ".");
        String startup = kv.containsKey("startup") ? kv.get("startup").getKey() : "";
        String pt = lower(ptype.getKey());
        if (pt.equals("exe") || pt.equals("oleexe")) {
            boolean startsForm = !startup.isEmpty() && !lower(startup).contains("sub main");
            String kind = forms > 0 || startsForm ? "desktop-app" : "main";
            facts.add("entry_points", Doc.of(Schemas.ENTRY_POINT,
                    "id", Fact.makeId("entry", path, name, kind),
                    "name", name,
                    "kind", kind,
                    "file", path,
                    "line", ptype.getValue(),
                    "evidence", new ArrayList<>(List.of(new Evidence(path, ptype.getValue(),
                            Rx.head("Type=" + ptype.getKey() + " Startup=" + startup, 120))))));
            if (startsForm) {
                facts.ref("forwards", "file:" + path, name, startup, List.of("desktop-form"), path, kv.get("startup").getValue())
                        .reportUnresolved(false);
            }
        }
    }

    private static void com(Facts facts, String path, int line, String desc, String version, String scope, String file) {
        String ver = version != null && VERSION_NUMBER.matcher(version).lookingAt() ? version : null;
        facts.add("libraries", BuildAnalyzer.library(null, desc, ver, "com", path, line, true, scope, VB6, null));
        String hint = desc + " " + file;
        for (ComTech ct : COM_TECH) {
            if (ct.rx().matcher(hint).find()) {
                facts.add("tech_stack", tech(ct.name(), null, ct.category(), path, line));
                break;
            }
        }
    }

    // ---- code -------------------------------------------------------------------------------

    private static final class Proc {
        final String name;
        final int line;
        final Tracker tracker;
        final Set<String> consumed = new HashSet<>();
        final List<String> blocks = new ArrayList<>();
        int lambdas;
        final List<String> attrs;
        final boolean shared;

        Proc(String name, int line, Tracker tracker, List<String> attrs, boolean shared) {
            this.name = name;
            this.line = line;
            this.tracker = tracker;
            this.attrs = attrs;
            this.shared = shared;
        }
    }

    private static final class VbClass {
        final String name;
        final String kind; // class, module, interface, structure, namespace
        final int line;
        String base = "";
        final List<String> attrs;

        VbClass(String name, String kind, int line, List<String> attrs) {
            this.name = name;
            this.kind = kind;
            this.line = line;
            this.attrs = attrs;
        }
    }

    private record SqlKey(int line, String text) {}

    private static boolean truthy(String s) {
        return s != null && !s.isEmpty();
    }

    private static boolean startsWithAny(String s, String... prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWithAny(String s, String... suffixes) {
        for (String p : suffixes) {
            if (s.endsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** One file's walk; not thread-safe. */
    private static final class Reader {
        private final String path;
        private final String lang;
        private final Facts facts;
        private final String text;
        private final String dialect;
        private final Tracker module = new Tracker();
        private final Set<String> moduleConsumed = new HashSet<>();
        private Proc proc;
        private final List<VbClass> classes = new ArrayList<>();
        private final Set<String> builders = new HashSet<>();
        private final Map<String, String> varTypes = new HashMap<>(); // variable -> class it was created from
        private List<String> pendingAttrs = new ArrayList<>();
        private String screenId;
        private String vbName;
        private final Set<SqlKey> sqlSeen = new HashSet<>();
        private final Set<SqlKey> urlSeen = new HashSet<>();
        private final boolean storedProc;
        private final boolean soap;
        private final boolean dotnet;

        Reader(FileRecord file, AnalyzerContext ctx, Facts facts, String text) {
            this.path = file.path;
            this.lang = file.language;
            this.facts = facts;
            this.text = text;
            this.dialect = ctx.config().sqlDialect();
            this.vbName = TextUtil.beforeLast(TextUtil.afterLast(path, "/"), ".");
            this.storedProc = SP_MARK.matcher(text).find() || lower(text).contains("storedprocedure");
            this.soap = SOAP_HINT.matcher(text).find();
            this.dotnet = lang.equals(VBNET);
        }

        private String adoSource() {
            return lang.equals(VB6) ? "ado" : "ado.net";
        }

        // ---- driving ------------------------------------------------------------------------

        void run() {
            List<String> lines = Arrays.asList(text.split("\n", -1));
            int start = 0;
            String low = lower(path);
            if (lang.equals(VB6) && VB6_EXTENSIONS.stream().anyMatch(low::endsWith)) {
                start = vb6HeaderEnd(lines);
                Matcher m = VB_NAME.matcher(text);
                if (m.find()) {
                    vbName = m.group(1);
                }
                if (VB6_FORMS.stream().anyMatch(low::endsWith)) {
                    vb6Form(lines.subList(0, start));
                }
                String last4 = low.length() >= 4 ? low.substring(low.length() - 4) : low;
                String kind = last4.equals(".bas") ? "vb-module" : last4.equals(".cls") ? "vb-class" : "";
                if (!kind.isEmpty()) {
                    facts.symbol(kind, vbName, path, 1);
                }
            }
            if (lang.equals(VB6)) {
                facts.add("tech_stack", tech("Visual Basic 6", null, "language", path, null));
            } else {
                facts.add("tech_stack", tech("VB.NET", null, "language", path, null));
            }
            List<Logical> logical = logicalLines(text, start);
            for (int idx = 0; idx < logical.size(); idx++) {
                String nxt = idx + 1 < logical.size() ? logical.get(idx + 1).code() : "";
                line(logical.get(idx).line(), logical.get(idx).code(), nxt);
            }
            endProc();
            leftovers(module, moduleConsumed, null);
        }

        private boolean cond() {
            return proc != null && proc.blocks.stream().anyMatch(COND_BLOCKS::contains);
        }

        private Tracker tracker() {
            return proc != null ? proc.tracker : module;
        }

        private Set<String> consumed() {
            return proc != null ? proc.consumed : moduleConsumed;
        }

        private void consume(String name, Built value) {
            consumed().add(name);
            if (module.get(name) == value) {
                moduleConsumed.add(name); // a module constant used here
            }
        }

        // ---- one logical line ---------------------------------------------------------------

        private void line(int line, String code, String nxt) {
            // attributes: <WebMethod()> Public Function ..., or on their own line
            while (true) {
                Matcher m = ATTRIBUTE.matcher(code);
                if (!m.lookingAt() || code.startsWith("<>")) {
                    break;
                }
                for (Piece a : splitTop(m.group(1), ",")) {
                    pendingAttrs.add(TextUtil.strip(a.text()));
                }
                code = TextUtil.strip(m.group(2));
                if (code.isEmpty()) {
                    return;
                }
            }
            String low = lower(code);
            if (low.startsWith("#")) { // #If, #Region, #Const
                return;
            }
            if (IMPORTS_OR_OPTION.matcher(code).lookingAt()) {
                imports(code, line);
                return;
            }
            Matcher t = TYPE.matcher(code);
            if (t.lookingAt() && !startsWithAny(low, "end ", "exit ")) {
                String kind = lower(t.group(1));
                if (!kind.equals("namespace")) {
                    classes.add(new VbClass(t.group(2), kind, line, pendingAttrs));
                    facts.symbol(kind.equals("module") ? "dotnet-module" : "dotnet-class", t.group(2), path, line);
                }
                pendingAttrs = new ArrayList<>();
                return;
            }
            if (TYPE_END.matcher(code).lookingAt()) {
                if (!low.startsWith("end namespace") && !classes.isEmpty()) {
                    classes.removeLast();
                }
                return;
            }
            Matcher inh = INHERITS.matcher(code);
            if (inh.lookingAt() && !classes.isEmpty()) {
                classes.getLast().base = inh.group(1);
                classKind(classes.getLast(), line);
                return;
            }
            if (proc != null && PROC_END.matcher(code).lookingAt()) {
                if (proc.lambdas > 0) {
                    proc.lambdas--;
                } else {
                    endProc();
                }
                return;
            }
            Matcher p = PROC.matcher(code);
            boolean inInterface = !classes.isEmpty() && classes.getLast().kind.equals("interface");
            if (p.lookingAt() && !inInterface && procStart(p, code, line, nxt)) {
                return;
            }
            if (proc == null && !dotnet && startsWithAny(low, "dim ", "private ", "public ", "global ", "const ")) {
                statement(code, line, false); // module-level constants
                return;
            }
            if (proc == null) {
                if (DECL.matcher(code).lookingAt()) {
                    statement(code, line, false); // VB.NET fields and constants
                }
                return;
            }
            if (LAMBDA_OPEN.matcher(code).find() && !PROC.matcher(code).lookingAt()) {
                proc.lambdas++;
            }
            blocks(code, line);
        }

        private boolean procStart(Matcher p, String code, int line, String nxt) {
            String kind = lower(p.group(1));
            if (MUST_OVERRIDE.matcher(code).find()) {
                return false;
            }
            // VB.NET: an auto-property has no body; a full one starts with Get/Set
            boolean accessor = ACCESSOR.matcher(nxt).lookingAt();
            if (kind.equals("property") && dotnet && !truthy(p.group(2)) && !accessor) {
                return false;
            }
            endProc();
            proc = new Proc(p.group(3), line, module.copy(), pendingAttrs, SHARED.matcher(code).find());
            pendingAttrs = new ArrayList<>();
            procMeaning(p.group(1), p.group(3), code, line);
            return true;
        }

        private void endProc() {
            if (proc == null) {
                return;
            }
            leftovers(proc.tracker, proc.consumed, module);
            proc = null;
        }

        private static void pop(List<String> blocks, String kind) {
            for (int i = blocks.size() - 1; i >= 0; i--) {
                if (blocks.get(i).equals(kind)) {
                    blocks.subList(i, blocks.size()).clear();
                    return;
                }
            }
        }

        private void blocks(String code, int line) {
            List<String> blocks = proc.blocks;
            String low = lower(code);
            Matcher m = IF_LINE.matcher(code);
            if (m.lookingAt()) {
                if (low.startsWith("elseif") || low.startsWith("else if")) {
                    exprUses(m.group(1), line);
                    return;
                }
                String rest = TextUtil.strip(m.group(2));
                exprUses(m.group(1), line);
                if (!rest.isEmpty()) { // single-line If: If x Then a = b Else c = d
                    for (String part : ELSE.split(rest, -1)) {
                        for (Piece stmt : splitTop(part, ":")) {
                            if (!TextUtil.strip(stmt.text()).isEmpty()) {
                                statement(TextUtil.strip(stmt.text()), line, true);
                            }
                        }
                    }
                    return;
                }
                blocks.add("if");
                return;
            }
            List<String> words = TextUtil.words(low);
            String word = words.isEmpty() ? "" : words.getFirst();
            if (low.startsWith("end if")) {
                pop(blocks, "if");
            } else if (low.startsWith("select case")) {
                blocks.add("select");
            } else if (low.startsWith("end select")) {
                pop(blocks, "select");
            } else if (word.equals("for")) {
                blocks.add("for");
            } else if (word.equals("next")) {
                pop(blocks, "for");
            } else if (word.equals("do")) {
                blocks.add("do");
            } else if (word.equals("loop")) {
                pop(blocks, "do");
            } else if (word.equals("while")) {
                blocks.add("while");
            } else if (low.equals("wend") || low.equals("end while")) {
                pop(blocks, "while");
            } else if (word.equals("try")) {
                blocks.add("try");
            } else if (word.equals("catch") || word.equals("finally")) {
                if (!blocks.isEmpty() && (blocks.getLast().equals("try") || blocks.getLast().equals("catch"))) {
                    blocks.set(blocks.size() - 1, "catch");
                }
            } else if (low.startsWith("end try")) {
                pop(blocks, blocks.contains("catch") ? "catch" : "try");
            } else if (word.equals("with") || word.equals("using") || word.equals("synclock")) {
                blocks.add(word);
                calls(code, line, true);
            } else if (startsWithAny(low, "end with", "end using", "end synclock")) {
                pop(blocks, words.get(1));
            } else if (word.equals("else") || word.equals("case")) {
                return;
            } else {
                for (Piece stmt : splitTop(code, ":")) {
                    if (!TextUtil.strip(stmt.text()).isEmpty()) {
                        statement(TextUtil.strip(stmt.text()), line, cond());
                    }
                }
            }
        }

        // ---- statements ---------------------------------------------------------------------

        private void statement(String code, int line, boolean cond) {
            String low = lower(code);
            if (SKIP_STATEMENT.matcher(low).lookingAt() && !low.startsWith("return ")) {
                return;
            }
            integrations(code, line);
            Matcher d = DECL.matcher(code);
            if (d.lookingAt()) {
                for (Piece part : splitTop(d.group(1), ",")) {
                    declarator(TextUtil.strip(part.text()), line, cond);
                }
                return;
            }
            Matcher a = ASSIGN.matcher(code);
            if (a.lookingAt() && !startsWithAny(low, "if ", "call ", "return ")) {
                assignment(a.group(1), a.group(2), TextUtil.strip(a.group(3)), line, cond);
                return;
            }
            if (builderCalls(code, line, cond)) {
                return;
            }
            calls(code, line, true);
        }

        private Built firstArg(String argsText, int line) {
            List<String> args = args(argsText == null ? "" : argsText);
            return args.isEmpty() ? null : eval(args.getFirst(), line, true);
        }

        private void declarator(String part, int line, boolean cond) {
            Matcher m = DECLARATOR.matcher(part);
            if (!m.lookingAt()) {
                return;
            }
            String name = lower(m.group(1));
            String type = lower(m.group(3) == null ? "" : m.group(3));
            if (m.group(2) != null && type.endsWith("stringbuilder")) {
                builders.add(name);
                Built first = firstArg(m.group(4), line);
                tracker().assign(name, first != null ? first : empty(line), line, cond, false);
                return;
            }
            String value = m.group(5);
            if (m.group(2) != null || lower(value == null ? "" : value).startsWith("new ")) {
                String cls = TextUtil.strip(TextUtil.beforeFirst(m.group(2) != null ? m.group(3) : value.substring(4), "("));
                varTypes.put(name, TextUtil.afterLast(cls, "."));
            }
            if (m.group(2) != null && truthy(m.group(4))) {
                calls("New " + m.group(3) + m.group(4), line, true);
            }
            if (value == null) {
                return;
            }
            Matcher nb = NEW_BUILDER.matcher(TextUtil.strip(value));
            if (nb.lookingAt()) {
                builders.add(name);
                Built first = firstArg(nb.group(1), line);
                tracker().assign(name, first != null ? first : empty(line), line, cond, false);
                return;
            }
            Built b = eval(value, line, false);
            calls(value, line, b == null);
            if (b != null) {
                tracker().assign(name, b, line, cond, false);
            }
        }

        private void assignment(String target, String op, String value, int line, boolean cond) {
            String tlow = lower(target);
            if (tlow.startsWith("me.")) {
                tlow = tlow.substring(3);
            }
            if (endsWithAny(tlow, ".commandtext", ".recordsource", ".sql") || Set.of("commandtext", "recordsource", ".commandtext").contains(tlow)) {
                Built b = eval(value, line, true);
                if (b != null) {
                    emit(b, adoSource(), line, false, true);
                }
                return;
            }
            if (endsWithAny(tlow, ".connectionstring", ".connect")) {
                Built b = eval(value, line, false);
                if (b != null) {
                    DotnetAnalyzer.addConnection(facts, b.text, path, line, adoSource(), "");
                }
                return;
            }
            if (!NAME.matcher(target).lookingAt()) {
                calls(value, line, true);
                return;
            }
            String name = tlow;
            Tracker tr = tracker();
            calls(value, line, eval(value, line, false) == null);
            if (op.equals("&=") || op.equals("+=")) {
                Built b = eval(value, line, false);
                if (b != null && tr.get(name) != null) {
                    tr.assign(name, b, line, cond, true);
                } else if (b != null) {
                    tr.assign(name, b, line, cond, false);
                }
                return;
            }
            Matcher nb = NEW_BUILDER.matcher(value);
            if (nb.lookingAt()) {
                builders.add(name);
                Built first = firstArg(nb.group(1), line);
                tr.assign(name, first != null ? first : empty(line), line, cond, false);
                return;
            }
            List<Piece> parts = splitTop(value, "&+");
            Matcher head = NAME.matcher(TextUtil.strip(parts.getFirst().text()));
            if (parts.size() > 1 && head.lookingAt() && lower(head.group(1)).equals(name) && tr.get(name) != null) {
                Built rest = evalParts(parts.subList(1, parts.size()), line, tr.get(name).text, false);
                if (rest != null) {
                    tr.assign(name, rest, line, cond, true);
                }
                return;
            }
            Built b = eval(value, line, false);
            if (b != null) {
                tr.assign(name, b, line, cond, false);
            } else if (tr.get(name) != null && !cond) {
                tr.remove(name); // now holds something else
            }
        }

        private boolean builderCalls(String code, int line, boolean cond) {
            Matcher m = BUILDER_START.matcher(code);
            if (!m.lookingAt() || !builders.contains(lower(m.group(1)))) {
                return false;
            }
            String name = lower(m.group(1));
            int pos = m.end() - 1;
            boolean done = false;
            while (true) {
                Matcher c = BUILDER_CALL.matcher(code);
                c.region(pos, code.length());
                if (!c.lookingAt()) {
                    break;
                }
                Balanced b = balanced(code, c.end() - 1);
                if (b == null) {
                    break;
                }
                List<String> args = args("(" + b.content() + ")");
                String verb = lower(c.group(1));
                Built value = null;
                if (verb.equals("appendformat") && !args.isEmpty()) {
                    value = format(args, line);
                } else if (!args.isEmpty()) {
                    value = eval(args.getFirst(), line, false);
                }
                if (verb.equals("appendline")) {
                    value = value != null ? concat(value, lit(" ", line)) : lit(" ", line);
                }
                if (value != null && !verb.equals("insert")) {
                    value.how.add("string-builder");
                    tracker().assign(name, value, line, cond, true);
                }
                done = true;
                pos = b.end();
            }
            return done;
        }

        /** Database calls, and ({@code uses}) SQL text passed to any other procedure. */
        private void calls(String code, int line, boolean uses) {
            boolean handled = false;
            Matcher m = DB_METHOD.matcher(code);
            while (m.find()) {
                String verb = lower(m.group(2));
                String rest = TextUtil.strip(code.substring(m.end()));
                List<String> args;
                if (rest.startsWith("(")) {
                    Balanced bal = balanced(rest, 0);
                    args = bal != null ? args("(" + bal.content() + ")") : List.of();
                } else if (!rest.isEmpty() && !rest.startsWith(".") && !rest.startsWith(")")) {
                    args = args(rest); // VB6: cn.Execute sql, , adCmdText
                } else {
                    args = List.of();
                }
                if (args.isEmpty()) {
                    continue;
                }
                String first = args.getFirst();
                if (verb.equals("open")) {
                    Built b0 = eval(first, line, false);
                    if (b0 != null && DotnetAnalyzer.connectionString(b0.text) != null) {
                        if (!NAME.matcher(first).lookingAt()) { // a variable is reported where it is set
                            DotnetAnalyzer.addConnection(facts, b0.text, path, line, "ado", "");
                        }
                        continue;
                    }
                }
                Built b = eval(first, line, true);
                if (b == null) {
                    continue;
                }
                String source;
                if (verb.equals("openrecordset") || verb.equals("createquerydef") || verb.equals("openresultset")) {
                    source = !verb.equals("openresultset") ? "dao" : "rdo";
                } else if (Set.of("sqlquery", "executesqlcommand", "executesqlraw", "fromsqlraw", "executesqlinterpolated", "fromsqlinterpolated",
                        "executesqlrawasync").contains(verb)) {
                    source = "ef-raw";
                } else if ((verb.startsWith("query") || verb.startsWith("execute")) && dotnet) {
                    source = "dapper"; // ADO.NET's Execute* take no SQL argument; Dapper's do
                } else {
                    source = adoSource();
                }
                if (verb.equals("createquerydef") && args.size() > 1) {
                    Built second = eval(args.get(1), line, true);
                    if (second != null) {
                        b = second;
                    }
                }
                emit(b, source, line, false, false);
                handled = true;
            }
            Matcher nc = NEW_COMMAND.matcher(code);
            while (nc.find()) {
                Balanced bal = balanced(code, nc.end() - 1);
                List<String> args = bal != null ? args("(" + bal.content() + ")") : List.of();
                if (!args.isEmpty()) {
                    Built b = eval(args.getFirst(), line, true);
                    if (b != null) {
                        emit(b, "ado.net", line, false, true);
                        handled = true;
                    }
                }
            }
            Matcher s = SHOW.matcher(code);
            while (s.find()) {
                String target = s.group(1) != null ? s.group(1) : s.group(2) != null ? s.group(2) : s.group(3);
                if (truthy(target)) {
                    target = varTypes.getOrDefault(lower(target), target);
                }
                if (truthy(target) && !Set.of("me", "msgbox", "form", "messagebox").contains(lower(target))) {
                    navigate(target, line);
                }
            }
            Matcher n = NEW_CLASS.matcher(code);
            while (n.find()) {
                String cls = n.group(1);
                if (!cls.contains(".") && !Set.of("stringbuilder", "object", "collection", "with").contains(lower(cls))) {
                    facts.ref("uses", "file:" + path, path, cls, List.of("vb-class", "dotnet-class"), path, line).reportUnresolved(false);
                }
            }
            if (handled || !uses) {
                return;
            }
            exprUses(code, line);
        }

        /** SQL held in a variable or written in place and handed to a procedure. */
        private void exprUses(String code, int line) {
            Tracker tr = tracker();
            // Python iterates a set here (its order changes from run to run); first appearance is used.
            Set<String> words = new LinkedHashSet<>();
            Matcher w = WORD.matcher(code);
            while (w.find()) {
                words.add(w.group(1));
            }
            for (String word : words) {
                String name = lower(word);
                Built value = tr.get(name);
                if (value != null && SqlTools.looksLikeSql(TextUtil.strip(value.text), true) && !consumed().contains(name)) {
                    consume(name, value);
                    emit(value, "vb-string", line, true, false);
                }
            }
            Matcher m = STRING.matcher(code);
            while (m.find()) {
                String s = unquote(m.group());
                if (SqlTools.looksLikeSql(TextUtil.strip(s), true)) {
                    // the whole expression the literal belongs to, rebuilt
                    String piece = expressionAround(code, m.start());
                    Built b = eval(piece, line, false);
                    emit(b != null ? b : lit(s, line), "vb-string", line, true, false);
                }
            }
        }

        // ---- values -------------------------------------------------------------------------

        private Built eval(String expr, int line, boolean consume) {
            expr = TextUtil.strip(expr);
            if (expr.isEmpty()) {
                return null;
            }
            List<Piece> parts = splitTop(expr, "&+");
            if (parts.size() == 1) {
                return atom(expr, line, consume);
            }
            return evalParts(parts, line, "", consume);
        }

        private Built evalParts(List<Piece> parts, int line, String before, boolean consume) {
            Built out = empty(line);
            boolean known = false;
            for (Piece piece : parts) {
                Built v = atom(TextUtil.strip(piece.text()), line, consume);
                if (v == null) {
                    v = hole(before + out.text, line);
                } else {
                    known = true;
                }
                out = concat(out, v);
            }
            if (!known) {
                return null;
            }
            out.how.add("concatenation");
            return out;
        }

        private Built atom(String a, int line, boolean consume) {
            if (a.isEmpty()) {
                return null;
            }
            if (a.startsWith("\"") && STRING.matcher(a).matches()) {
                return lit(unquote(a), line);
            }
            if (a.startsWith("(") && a.endsWith(")")) {
                Balanced b = balanced(a, 0);
                if (b != null && b.end() == a.length()) {
                    return eval(b.content(), line, consume);
                }
            }
            if (NEWLINES.matcher(a).lookingAt()) {
                return lit(" ", line);
            }
            Matcher c = CHR.matcher(a);
            if (c.lookingAt()) {
                int code;
                try {
                    code = Integer.parseInt(c.group(1));
                } catch (NumberFormatException e) {
                    code = -1; // a very long number: not one of the characters below
                }
                if (code == 39 || code == 34 || code == 9 || code == 10 || code == 13 || code == 32) {
                    return lit(code == 39 ? "'" : code == 34 ? "\"" : " ", line);
                }
                return null;
            }
            Matcher m = NAME.matcher(a);
            if (m.lookingAt()) {
                String name = lower(m.group(1));
                Built value = tracker().get(name);
                if (value != null && consume) {
                    consume(name, value);
                }
                return value;
            }
            Matcher tb = TO_STRING.matcher(a);
            if (tb.lookingAt() && builders.contains(lower(tb.group(1)))) {
                String key = lower(tb.group(1));
                Built value = tracker().get(key);
                if (value != null && consume) {
                    consume(key, value);
                }
                return value;
            }
            Matcher f = FORMAT_CALL.matcher(a);
            if (f.lookingAt() && lower(a).startsWith("string.format")) {
                Balanced b = balanced(a, f.end() - 1);
                if (b != null) {
                    return format(args("(" + b.content() + ")"), line);
                }
            }
            Matcher i = IIF.matcher(a);
            if (i.lookingAt()) {
                Balanced b = balanced(a, i.end() - 1);
                List<String> args = b != null ? args("(" + b.content() + ")") : List.of();
                if (args.size() == 3) {
                    Built x = eval(args.get(1), line, false);
                    Built y = eval(args.get(2), line, false);
                    if (x != null && y != null) {
                        return choice(x, y);
                    }
                    return x != null ? x : y;
                }
            }
            Matcher s = CONCAT.matcher(a);
            if (s.lookingAt()) {
                Balanced b = balanced(a, s.end() - 1);
                if (b != null) {
                    List<Piece> parts = new ArrayList<>();
                    for (String p : args("(" + b.content() + ")")) {
                        parts.add(new Piece("&", p));
                    }
                    return evalParts(parts, line, "", false);
                }
            }
            return null;
        }

        private Built format(List<String> args, int line) {
            if (args.isEmpty()) {
                return null;
            }
            Built fmt = eval(args.getFirst(), line, false);
            if (fmt == null) {
                return null;
            }
            List<Built> values = new ArrayList<>();
            for (String x : args.subList(1, args.size())) {
                values.add(eval(x, line, false));
            }
            Built out = empty(line);
            int pos = 0;
            Matcher m = FORMAT_HOLE.matcher(fmt.text);
            while (m.find()) {
                out = concat(out, lit(fmt.text.substring(pos, m.start()), line));
                int k = Integer.parseInt(m.group(1));
                Built v = k < values.size() ? values.get(k) : null;
                out = concat(out, v != null ? v : hole(out.text, line));
                pos = m.end();
            }
            out = concat(out, lit(fmt.text.substring(pos), line));
            out.how.add("string-format");
            return out;
        }

        // ---- results ------------------------------------------------------------------------

        private void emit(Built b, String source, int line, boolean strict, boolean procOk) {
            String text = TextUtil.strip(b.text);
            if (procOk && storedProc && PROC_NAME.matcher(text).lookingAt() && !SqlTools.looksLikeSql(text)) {
                text = "CALL " + text;
                Built nb = new Built(text, b.line);
                nb.lines = new LinkedHashSet<>(b.lines);
                b = nb;
            }
            if (!SqlTools.looksLikeSql(text, strict)) {
                return;
            }
            if (!sqlSeen.add(new SqlKey(line, text))) {
                return;
            }
            Doc fact = SqlTools.sqlFact(path, line, text, source, dialect, b.dynamic(), false);
            DynSql.fill(fact, b);
            facts.add("sql_statements", fact);
        }

        /** SQL text kept in a variable that no call used here (returned, passed on). */
        private void leftovers(Tracker tr, Set<String> consumed, Tracker inherited) {
            for (var e : new ArrayList<>(tr.current().entrySet())) {
                if (consumed.contains(e.getKey())) {
                    continue;
                }
                if (inherited != null && inherited.get(e.getKey()) == e.getValue()) {
                    continue; // a module constant: reported with the module
                }
                emit(e.getValue(), "vb-string", e.getValue().line, true, false);
            }
        }

        private void navigate(String target, int line) {
            facts.ref("forwards", screenId != null ? "screen:" + screenId : "file:" + path, vbName, target, List.of("desktop-form"), path, line)
                    .reportUnresolved(false);
        }

        private record Lit(String text, int start) {}

        private void integrations(String code, int line) {
            List<Lit> lits = new ArrayList<>();
            Matcher sm = STRING.matcher(code);
            while (sm.find()) {
                lits.add(new Lit(unquote(sm.group()), sm.start()));
            }
            for (Lit l : lits) {
                String s = l.text();
                if (s.contains("://") && !urlSeen.contains(new SqlKey(line, s))) {
                    urlSeen.add(new SqlKey(line, s));
                    Matcher u = URL.matcher(s);
                    while (u.find()) {
                        String[] c = Integrations.classifyUrl(u.group(), soap);
                        if (c != null) {
                            facts.add("integrations", integration(c[0], c[1], "outbound", path, line));
                        }
                    }
                } else if (s.contains(";") && s.contains("=") && !SETS_CONNECTION.matcher(code).find()) {
                    DotnetAnalyzer.addConnection(facts, s, path, line, adoSource(), "");
                }
            }
            Matcher f = OPEN_FILE.matcher(code);
            if (f.lookingAt()) {
                Built b = eval(f.group(1), line, false);
                if (b != null && !TextUtil.strip(b.text, "? ").isEmpty()) {
                    String mode = lower(f.group(2));
                    String direction = mode.equals("input") ? "inbound" : mode.equals("output") || mode.equals("append") ? "outbound" : "both";
                    file(b.text, direction, line, "VB Open");
                }
            }
            Matcher m = DOTNET_FILE.matcher(code);
            while (m.find()) {
                Balanced b0 = balanced(code, m.end() - 1);
                List<String> args = b0 != null ? args("(" + b0.content() + ")") : List.of();
                Built b = args.isEmpty() ? null : eval(args.getFirst(), line, false);
                if (b == null || TextUtil.strip(b.text, "? ").isEmpty()) {
                    continue;
                }
                String what = lower(m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : "");
                boolean out = startsWithAny(what, "write", "append", "openwrite", "create", "streamwriter");
                file(b.text, out ? "outbound" : "inbound", line, TextUtil.rstrip(m.group(), "("));
            }
            if (MSMQ.matcher(code).find()) {
                String q = null;
                for (Lit l : lits) {
                    if (l.text().contains("\\") || startsWithAny(lower(l.text()), "formatname:", "direct=")) {
                        q = l.text();
                        break;
                    }
                }
                if (truthy(q)) {
                    facts.add("integrations", integration("MQ", q, "unknown", path, line, null, "MSMQ", null));
                }
            }
            if (MQ_ACCESS.matcher(code).find() && !lits.isEmpty()) {
                facts.add("integrations", integration("MQ", lits.getFirst().text(), "unknown", path, line, null, "IBM MQ (MQAX)", null));
            }
            if (WINSOCK.matcher(code).find() && !lits.isEmpty() && HOST_NAME.matcher(lits.getFirst().text()).lookingAt()
                    && SOCKET_HINT.matcher(code).find()) {
                facts.add("integrations", integration("SOCKET", lits.getFirst().text(), "outbound", path, line, null, "Winsock", null));
            }
            if (SMTP.matcher(code).find() && !lits.isEmpty() && MAIL_HOST.matcher(lits.getLast().text()).lookingAt() && MAIL_HINT.matcher(code).find()) {
                facts.add("integrations", integration("SMTP", lits.getLast().text(), "outbound", path, line));
            }
        }

        private void file(String target, String direction, int line, String how) {
            facts.add("integrations", integration("FILE", TextUtil.strip(target), direction, path, line, null, how, null));
        }

        private void imports(String code, int line) {
            Matcher m = IMPORTS.matcher(code);
            if (!m.lookingAt()) {
                return;
            }
            String ns = m.group(1);
            for (DotnetAnalyzer.NamespaceTech nt : DotnetAnalyzer.NAMESPACE_TECH) {
                if (ns.equals(nt.namespace()) || ns.startsWith(nt.namespace() + ".")) {
                    facts.add("tech_stack", tech(nt.tech(), null, nt.category(), path, line));
                    break;
                }
            }
            if (DotnetAnalyzer.DB_NAMESPACES.containsKey(ns)) {
                facts.add("tech_stack", tech(DotnetAnalyzer.DB_NAMESPACES.get(ns), null, "database", path, line));
            }
        }

        // ---- forms, classes and services ----------------------------------------------------

        private void vb6Form(List<String> header) {
            FormHeader fh = parseFormHeader(header);
            Control root = fh.root();
            if (root == null) {
                return;
            }
            String rtype = lower(TextUtil.afterLast(root.type, "."));
            boolean partial = Set.of("usercontrol", "propertypage", "userdocument").contains(rtype);
            String sid = Fact.makeId("screen", path);
            screenId = sid;
            String caption = root.props.containsKey("caption") ? root.props.get("caption").getKey() : "";
            long fields = fh.controls().stream().filter(c -> INPUT_CONTROLS.matcher(TextUtil.afterLast(c.type, ".")).lookingAt()).count();
            facts.add("screens", Doc.of(Schemas.SCREEN,
                    "id", sid,
                    "name", vbName,
                    "technology", "vb6",
                    "file", path,
                    "fields_count", fields,
                    "partial", partial,
                    "evidence", new ArrayList<>(List.of(new Evidence(path, root.line,
                            root.type + " " + root.name + (caption.isEmpty() ? "" : ": " + caption))))));
            facts.symbol("desktop-form", vbName, path, root.line).owner(sid);
            for (Control c : fh.controls()) {
                String ctype = lower(TextUtil.afterLast(c.type, "."));
                Map.Entry<String, Integer> rs = c.props.get("recordsource");
                if (rs != null && SqlTools.looksLikeSql(TextUtil.strip(rs.getKey()))) {
                    emit(lit(rs.getKey(), rs.getValue()), ctype.equals("adodc") ? "ado" : "dao", rs.getValue(), false, false);
                }
                Map.Entry<String, Integer> cs = c.props.containsKey("connectionstring") ? c.props.get("connectionstring") : c.props.get("connect");
                if (cs != null) {
                    DotnetAnalyzer.addConnection(facts, cs.getKey(), path, cs.getValue(), "ado", "");
                }
                Map.Entry<String, Integer> db = c.props.get("databasename");
                if (db != null && !db.getKey().isEmpty()) {
                    facts.add("integrations", integration("DB", "access://" + TextUtil.afterLast(db.getKey().replace("\\", "/"), "/"), "outbound", path,
                            db.getValue(), null, "dao", null));
                    facts.add("tech_stack", tech("Microsoft Access", null, "database", path, db.getValue()));
                }
                for (String key : List.of("remotehost", "url")) {
                    Map.Entry<String, Integer> v = c.props.get(key);
                    if (v != null && !v.getKey().isEmpty()) {
                        if (v.getKey().contains("://")) {
                            String[] cu = Integrations.classifyUrl(v.getKey());
                            if (cu != null) {
                                facts.add("integrations", integration(cu[0], cu[1], "outbound", path, v.getValue()));
                            }
                        } else if (ctype.equals("winsock")) {
                            facts.add("integrations", integration("SOCKET", v.getKey(), "outbound", path, v.getValue(), null, "Winsock", null));
                        }
                    }
                }
            }
        }

        private void classKind(VbClass cls, int line) {
            String base = lower(TextUtil.afterLast(cls.base, "."));
            String kind = BASES.get(base);
            if ("form".equals(kind) || "usercontrol".equals(kind)) {
                String sid = Fact.makeId("screen", "winforms", TextUtil.dirname(path), cls.name);
                screenId = sid;
                facts.add("screens", Doc.of(Schemas.SCREEN,
                        "id", sid,
                        "name", cls.name,
                        "technology", "winforms",
                        "file", path,
                        "fields_count", (long) designerFields(text),
                        "partial", kind.equals("usercontrol"),
                        "evidence", new ArrayList<>(List.of(new Evidence(path, line, "Inherits " + cls.base)))));
                facts.symbol("desktop-form", cls.name, path, line).owner(sid);
                facts.add("tech_stack", tech("Windows Forms", null, "framework", path, line));
            } else if ("windows-service".equals(kind) || "background-service".equals(kind)) {
                facts.add("entry_points", Doc.of(Schemas.ENTRY_POINT,
                        "id", Fact.makeId("entry", path, cls.name, "windows-service"),
                        "name", cls.name,
                        "kind", "windows-service",
                        "file", path,
                        "line", line,
                        "evidence", new ArrayList<>(List.of(new Evidence(path, line, "Inherits " + cls.base)))));
            } else if ("dbcontext".equals(kind)) {
                Matcher m = DBSET_PROPERTY.matcher(text);
                while (m.find()) {
                    String entity = TextUtil.afterLast(m.group(2), ".");
                    facts.symbol("jpa-entity", m.group(1), path, TextUtil.lineAt(text, m.start()))
                            .data("table", entity.toUpperCase(Locale.ROOT)).data("entity", entity).data("orm", "ef");
                }
            }
        }

        private static List<String> attrNames(List<String> attrs) {
            List<String> out = new ArrayList<>();
            for (String a : attrs) {
                Matcher m = ATTR_NAME.matcher(a);
                if (m.lookingAt()) {
                    out.add(lower(TextUtil.afterLast(m.group(1), ".")));
                }
            }
            return out;
        }

        /** What a procedure stands for: an entry point, a web method, a controller action. */
        private void procMeaning(String what, String name, String code, int line) {
            VbClass cls = classes.isEmpty() ? null : classes.getLast();
            if (lower(name).equals("main") && lower(what).equals("sub")) {
                String kind = DESKTOP_MAIN.matcher(procBody(text, line)).find() ? "desktop-app" : "main";
                facts.add("entry_points", Doc.of(Schemas.ENTRY_POINT,
                        "id", Fact.makeId("entry", path, "Main", kind),
                        "name", (cls != null ? cls.name : vbName) + ".Main",
                        "kind", kind,
                        "file", path,
                        "line", line,
                        "evidence", new ArrayList<>(List.of(new Evidence(path, line, Rx.head(code, 120))))));
                return;
            }
            if (cls == null || (!MEANING_START.matcher(code).lookingAt() && !(proc != null && !proc.attrs.isEmpty()))) {
                return;
            }
            List<String> attrs = proc != null ? proc.attrs : List.of();
            List<String> names = attrNames(attrs);
            List<String> clsAttrs = attrNames(cls.attrs);
            String base = BASES.getOrDefault(lower(TextUtil.afterLast(cls.base, ".")), "");
            String handler = cls.name + "." + name;
            if (names.contains("webmethod") || names.contains("operationcontract") || names.contains("operationcontractattribute")) {
                String how = !names.contains("webmethod") ? "WCF" : "ASMX";
                String url = cls.name + "." + (how.equals("ASMX") ? "asmx" : "svc") + "/" + name;
                String fw = how.equals("WCF") ? "WCF" : "ASP.NET Web Services (ASMX)";
                service(how + " " + cls.name + "." + name, "soap-operation", handler, url, line, fw, cls.name);
                facts.add("tech_stack", tech(fw, null, "framework", path, line));
                return;
            }
            if (base.equals("mvc") || base.equals("api") || clsAttrs.contains("apicontroller")) {
                if (!PUBLIC.matcher(code).find() || SHARED.matcher(code).find()) {
                    return;
                }
                String verb = null;
                for (String n : names) {
                    Matcher h = HTTP_ATTR.matcher(n);
                    if (h.lookingAt()) {
                        verb = h.group(1).toUpperCase(Locale.ROOT);
                        break;
                    }
                }
                String route = route(attrs, List.of("Route", "HttpGet", "HttpPost", "HttpPut", "HttpDelete", "HttpPatch"));
                String prefix = route(cls.attrs, List.of("Route", "RoutePrefix"));
                String ctrl = cls.name.endsWith("Controller") ? cls.name.substring(0, cls.name.length() - "Controller".length()) : cls.name;
                if (prefix != null) {
                    prefix = prefix.replace("[controller]", ctrl);
                } else {
                    prefix = base.equals("api") ? "api/" + ctrl : ctrl;
                }
                String urlPath;
                if (route != null && route.startsWith("/")) {
                    urlPath = route;
                } else {
                    urlPath = "/" + TextUtil.strip(prefix, "/") + (truthy(route) ? "/" + route : base.equals("api") ? "" : "/" + name);
                }
                urlPath = urlPath.replace("[action]", name);
                String kind = base.equals("api") || clsAttrs.contains("apicontroller") ? "rest-operation" : "aspnet-mvc";
                String shown = verb != null ? verb : kind.equals("aspnet-mvc") ? "GET" : "ANY";
                service(shown + " " + urlPath, kind, handler, urlPath, line, kind.equals("rest-operation") ? "ASP.NET Web API" : null, null);
            }
        }

        private void service(String name, String kind, String handler, String url, int line, String framework, String webService) {
            String sid = Fact.makeId("service", kind, path, name);
            facts.add("services", Doc.of(Schemas.SERVICE,
                    "id", sid,
                    "name", name,
                    "kind", kind,
                    "file", path,
                    "handler", handler,
                    "url", url,
                    "framework", framework,
                    "web_service", webService,
                    "evidence", new ArrayList<>(List.of(new Evidence(path, line, null)))));
            facts.symbol("service", handler, path, line).owner(sid);
        }
    }

    private static String route(List<String> attrs, List<String> names) {
        for (String a : attrs) {
            Matcher m = ROUTE.matcher(a);
            if (m.lookingAt() && names.contains(m.group(1))) {
                return m.group(2);
            }
        }
        return null;
    }

    private static String procBody(String text, int line) {
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>();
        for (int i = line; i < lines.length; i++) {
            if (PROC_END.matcher(TextUtil.strip(lines[i])).lookingAt()) {
                break;
            }
            out.add(lines[i]);
        }
        return String.join("\n", out);
    }

    /** Input controls created by a Windows Forms designer ({@code Me.txtName = New TextBox()}). */
    static int designerFields(String text) {
        int n = 0;
        Matcher m = DESIGNER_NEW.matcher(text);
        while (m.find()) {
            if (INPUT_CONTROLS.matcher(m.group(1)).lookingAt()) {
                n++;
            }
        }
        return n;
    }

    /** The comma-separated argument (or right-hand side) that holds the character at {@code pos}. */
    static String expressionAround(String code, int pos) {
        int start = 0;
        int depth = 0;
        boolean inStr = false;
        for (int i = 0; i < pos; i++) {
            char ch = code.charAt(i);
            if (ch == '"') {
                inStr = !inStr;
            } else if (inStr) {
                continue;
            } else if (ch == '(') {
                depth++;
                start = i + 1;
            } else if (ch == ')') {
                depth--;
            } else if (ch == ',' || ch == '=' || (ch == ' ' && lower(TextUtil.strip(code.substring(0, i))).equals("return"))) {
                start = i + 1;
            }
        }
        int end = code.length();
        depth = 0;
        inStr = false;
        for (int i = pos; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == '"') {
                inStr = !inStr;
            } else if (inStr) {
                continue;
            } else if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                if (depth == 0) {
                    end = i;
                    break;
                }
                depth--;
            } else if (ch == ',' && depth == 0) {
                end = i;
                break;
            }
        }
        return TextUtil.strip(code.substring(start, end));
    }
}
