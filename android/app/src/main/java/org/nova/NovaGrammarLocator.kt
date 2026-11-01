package org.nova

import io.noties.prism4j.GrammarLocator
import io.noties.prism4j.Prism4j
import java.util.regex.Pattern

/**
 * Hand-built Prism4j grammars for common languages - lightweight code
 * highlighting without the annotation-processor machinery. Unknown
 * languages fall back to a generic programming grammar.
 */
object NovaGrammarLocator : GrammarLocator {

    private fun p(regex: String) = Prism4j.pattern(Pattern.compile(regex))
    private fun t(name: String, vararg patterns: Prism4j.Pattern) = Prism4j.token(name, *patterns)
    private fun g(name: String, vararg tokens: Prism4j.Token) = Prism4j.grammar(name, *tokens)

    private fun cLike(name: String, keywords: String, line: String = "//[^\\n]*"): Prism4j.Grammar =
        g(name,
            t("comment", p("/\\*[\\s\\S]*?\\*/"), p(line)),
            t("string", p("\"(?:\\\\.|[^\\\\\"\\\\n])*\""), p("'(?:\\\\.|[^\\\\'\\\\n])*'")),
            t("keyword", p("\\b(?:${keywords.replace(" ", "|")})\\b")),
            t("number", p("\\b(?:0[xX][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)[uUlLfFdD]?\\b")),
            t("annotation", p("@[A-Za-z_][A-Za-z0-9_.]*")),
            t("function", p("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\()")),
            t("operator", p("[+\\-*/%=<>!&|^~?:]+")),
            t("punctuation", p("[{}()\\[\\];,.]"))
        )

    private fun python(): Prism4j.Grammar = g("python",
            t("comment", p("#[^\\n]*")),
            t("string", p("\"\"\"[\\s\\S]*?\"\"\""), p("'''[\\s\\S]*?'''"), p("\"(?:\\\\.|[^\\\\\"\\\\n])*\""), p("'(?:\\\\.|[^\\\\'\\\\n])*'")),
            t("keyword", p("\\b(?:and|as|assert|async|await|break|class|continue|def|del|elif|else|except|finally|for|from|global|if|import|in|is|lambda|nonlocal|not|or|pass|raise|return|try|while|with|yield|True|False|None|self)\\b")),
            t("decorator", p("@[A-Za-z_][A-Za-z0-9_.]*")),
            t("function", p("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\()")),
            t("number", p("\\b\\d+(?:\\.\\d+)?\\b")),
            t("operator", p("[+\\-*/%=<>!&|^~]+")),
            t("punctuation", p("[{}()\\[\\];,.:]"))
        )

    private fun bash(): Prism4j.Grammar = g("bash",
            t("comment", p("#[^\\n]*")),
            t("string", p("\"(?:\\\\.|[^\\\\\"])*\""), p("'[^']*'"), p("`[^`]*`")),
            t("keyword", p("\\b(?:if|then|else|elif|fi|for|while|until|do|done|case|esac|function|in|return|exit|export|local|readonly|source|alias|echo|cd|break|continue)\\b")),
            t("number", p("\\b\\d+\\b")),
            t("function", p("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\()")),
            t("operator", p("[|&;<>()=!]+")),
            t("punctuation", p("[{}\\[\\];,]"))
        )

    private fun json(): Prism4j.Grammar = g("json",
            t("string", p("\"(?:\\\\.|[^\\\\\"])*\"")),
            t("number", p("-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b")),
            t("boolean", p("\\b(?:true|false|null)\\b")),
            t("punctuation", p("[{}\\[\\],:]"))
        )

    private fun markup(): Prism4j.Grammar = g("html",
            t("comment", p("<!--[\\s\\S]*?-->")),
            t("tag", p("</?[A-Za-z][A-Za-z0-9-]*")),
            t("attr-name", p("\\b[A-Za-z-]+(?==)")),
            t("string", p("\"[^\"]*\"|'[^']*'")),
            t("punctuation", p("[<>/=]"))
        )

    private fun css(): Prism4j.Grammar = g("css",
            t("comment", p("/\\*[\\s\\S]*?\\*/")),
            t("string", p("\"[^\"]*\"|'[^']*'")),
            t("property", p("[A-Za-z-]+(?=\\s*:)")),
            t("number", p("#[0-9a-fA-F]{3,8}\\b|\\b\\d+(?:\\.\\d+)?(?:px|em|rem|%|vh|vw|s|ms)?\\b"))
        )

    private fun sql(): Prism4j.Grammar = g("sql",
            t("comment", p("--[^\\n]*"), p("/\\*[\\s\\S]*?\\*/")),
            t("string", p("'[^']*'")),
            t("keyword", p("(?i)\\b(?:select|from|where|group|by|order|having|limit|offset|insert|into|values|update|set|delete|create|table|alter|drop|join|left|right|inner|outer|on|as|and|or|not|null|like|in|between|union|all|distinct|count|sum|avg|min|max|primary|key|foreign|references|index|view|if|exists|case|when|then|else|end)\\b")),
            t("number", p("\\b\\d+(?:\\.\\d+)?\\b")),
            t("punctuation", p("[(),;.*]"))
        )

    private fun yaml(): Prism4j.Grammar = g("yaml",
            t("comment", p("#[^\\n]*")),
            t("string", p("\"(?:\\\\.|[^\\\\\"])*|'[^']*'")),
            t("property", p("^[ \\t-]*[A-Za-z_][A-Za-z0-9_-]*(?=\\s*:)")),
            t("boolean", p("\\b(?:true|false|null|yes|no|on|off)\\b")),
            t("number", p("\\b\\d+(?:\\.\\d+)?\\b"))
        )

    private fun generic(): Prism4j.Grammar = g("generic",
            t("comment", p("//[^\\n]*|#[^\\n]*")),
            t("string", p("\"(?:\\\\.|[^\\\\\"\\\\n])*|'(?:\\\\.|[^\\\\'\\\\n])*'")),
            t("keyword", p("\\b(?:if|else|elif|for|while|loop|fn|func|function|def|class|struct|enum|impl|return|break|continue|let|var|const|val|new|true|false|null|nil|void|end|then|do|try|catch|throw)\\b")),
            t("number", p("\\b\\d+(?:\\.\\d+)?\\b")),
            t("function", p("\\b[A-Za-z_][A-Za-z0-9_]*(?=\\s*\\()")),
            t("operator", p("[+\\-*/%=<>!&|^~?:]+"))
        )

    private val grammars: Map<String, Prism4j.Grammar> by lazy {
        mapOf(
            "kotlin" to cLike("kotlin", "abstract as assert break by catch class companion const continue crossinline data do dynamic else enum expect external false final finally for fun get if import in infix init inline interface internal is lateinit null object open operator out override package private protected public reified return sealed set super suspend tailrec this throw true try typealias val var vararg when where while"),
            "java" to cLike("java", "abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null var record"),
            "javascript" to cLike("javascript", "async await break case catch class const continue debugger default delete do else export extends false finally for function if import in instanceof let new null of return switch this throw true try typeof undefined var void while with yield"),
            "cpp" to cLike("cpp", "alignas alignof auto bool break case catch char class const constexpr continue decltype default delete do double dynamic_cast else enum explicit export extern false float for friend goto if inline int long mutable namespace new noexcept nullptr operator private protected public register return short signed sizeof static static_cast struct switch template this throw true try typedef typeid typename union unsigned using virtual void volatile while"),
            "csharp" to cLike("csharp", "abstract as base bool break byte case catch char checked class const continue decimal default delegate do double else enum event explicit extern false finally fixed float for foreach goto if implicit in int interface internal is lock long namespace new null object operator out override params private protected public readonly ref return sbyte sealed short sizeof stackalloc static string struct switch this throw true try typeof uint ulong unchecked unsafe ushort using var virtual void volatile while"),
            "go" to cLike("go", "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var true false nil"),
            "rust" to cLike("rust", "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self static struct super trait true type unsafe use where while"),
            "swift" to cLike("swift", "associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private protocol public rethrows static struct subscript typealias var break case continue default defer do else fallthrough for guard if in repeat return switch where while as catch is nil super self throw throws try true false"),
            "php" to cLike("php", "abstract and array as break callable case catch class clone const continue declare default do echo else elseif enddeclare endfor endforeach endif endswitch endwhile extends final finally fn for foreach function global if implements include instanceof insteadof interface isset list namespace new or print private protected public require return static switch throw trait try unset use var while xor true false null"),
            "python" to python(),
            "bash" to bash(),
            "json" to json(),
            "html" to markup(),
            "xml" to markup(),
            "css" to css(),
            "sql" to sql(),
            "yaml" to yaml(),
            "generic" to generic()
        )
    }

    override fun languages(): Set<String> = grammars.keys

    override fun grammar(prism4j: Prism4j, language: String): Prism4j.Grammar? {
        val l = language.trim().lowercase()
        return when (l) {
            "js", "ts", "jsx", "tsx", "node", "mjs" -> grammars["javascript"]
            "py", "python3" -> grammars["python"]
            "c++", "cxx", "cc", "hpp" -> grammars["cpp"]
            "cs" -> grammars["csharp"]
            "shell", "sh", "zsh" -> grammars["bash"]
            "yml" -> grammars["yaml"]
            "htm", "xhtml" -> grammars["html"]
            "json5", "jsonc" -> grammars["json"]
            "rs" -> grammars["rust"]
            "golang" -> grammars["go"]
            "kt" -> grammars["kotlin"]
            "", "text", "plaintext", "none", "txt" -> null
            else -> grammars[l] ?: grammars["generic"]
        }
    }
}
