package jp.studycopi

import kotlin.random.Random

val wakeSubjects = listOf("math" to "数学", "english" to "英語", "history" to "世界史", "mixed" to "おまかせ（3教科）")
fun wakeSubjectLabel(id: String) = wakeSubjects.first { it.first == id }.second
data class WakeQuiz(val text: String, val answer: String, val choices: List<String> = emptyList()) {
    fun accepts(value: String) = if (choices.isEmpty()) value.toIntOrNull()?.toString() == answer else value == answer
}

/** Offline, fixed question banks. Shuffle once per run so both stages avoid repeated questions. */
object WakeQuizBank {
    private fun word(english: String, japanese: String, vararg other: String) = WakeQuiz("「$english」の意味は？", japanese, listOf(japanese) + other)
    val english = listOf(
        word("library", "図書館", "病院", "駅", "空港"), word("quiet", "静かな", "忙しい", "明るい", "重い"),
        word("borrow", "借りる", "貸す", "買う", "売る"), word("arrive", "到着する", "出発する", "忘れる", "選ぶ"),
        word("careful", "注意深い", "危険な", "退屈な", "眠い"), word("although", "〜だけれども", "〜だから", "〜するために", "〜の前に"),
        word("improve", "改善する", "壊す", "隠す", "減らす"), word("environment", "環境", "約束", "経験", "発明"),
        word("necessary", "必要な", "有名な", "高価な", "自由な"), word("decide", "決める", "説明する", "招待する", "失敗する"),
        word("perhaps", "おそらく", "決して〜ない", "すでに", "すぐに"), word("invite", "招待する", "拒否する", "集める", "数える"),
        word("instead", "代わりに", "一緒に", "とても", "めったに〜ない"), word("knowledge", "知識", "力", "健康", "財産"),
        word("remember", "覚えている", "忘れる", "繰り返す", "許す"), word("journey", "旅", "仕事", "会議", "試験"),
        word("enough", "十分な", "少ない", "同じ", "別の"), word("discover", "発見する", "破壊する", "輸入する", "比較する"),
        word("include", "含む", "避ける", "届ける", "守る"), word("honest", "正直な", "親切な", "勇敢な", "賢い")
    )
    private fun history(question: String, answer: String, vararg other: String) = WakeQuiz(question, answer, listOf(answer) + other)
    val history = listOf(
        history("フランス革命が始まった年は？", "1789年", "1689年", "1815年", "1848年"),
        history("第一次世界大戦が始まった年は？", "1914年", "1905年", "1917年", "1939年"),
        history("第二次世界大戦が始まった年は？", "1939年", "1918年", "1929年", "1945年"),
        history("古代エジプト文明が栄えた川は？", "ナイル川", "黄河", "インダス川", "ライン川"),
        history("メソポタミア文明の地域を流れる川の組み合わせは？", "ティグリス川・ユーフラテス川", "ナイル川・コンゴ川", "黄河・長江", "ライン川・ドナウ川"),
        history("中国を初めて統一した秦の皇帝は？", "始皇帝", "武帝", "煬帝", "洪武帝"),
        history("モンゴル帝国を建てた人物は？", "チンギス・ハン", "フビライ・ハン", "ティムール", "スレイマン1世"),
        history("元を建てた人物は？", "フビライ・ハン", "チンギス・ハン", "朱元璋", "李世民"),
        history("明を建てた人物は？", "朱元璋", "李世民", "フビライ・ハン", "孫文"),
        history("イスラームを開いた人物は？", "ムハンマド", "イエス", "ブッダ", "孔子"),
        history("ローマ帝国の初代皇帝は？", "アウグストゥス", "ネロ", "カエサル", "コンスタンティヌス"),
        history("東ローマ帝国の首都は？", "コンスタンティノープル", "ローマ", "アテネ", "アレクサンドリア"),
        history("『モナ・リザ』を描いた人物は？", "レオナルド・ダ・ヴィンチ", "ミケランジェロ", "ラファエロ", "ボッティチェリ"),
        history("『九十五か条の論題』で宗教改革を始めた人物は？", "ルター", "カルヴァン", "エラスムス", "ロック"),
        history("産業革命が最初に起こった国は？", "イギリス", "フランス", "ドイツ", "アメリカ"),
        history("アメリカ独立宣言が出された年は？", "1776年", "1789年", "1815年", "1861年"),
        history("ロシア革命が起こった年は？", "1917年", "1848年", "1900年", "1929年"),
        history("世界恐慌が始まった年は？", "1929年", "1914年", "1918年", "1945年"),
        history("国際連合が発足した年は？", "1945年", "1919年", "1939年", "1955年"),
        history("ベルリンの壁が崩壊した年は？", "1989年", "1961年", "1979年", "1991年")
    )
    fun forRun(run: WakeRun): WakeQuiz {
        val index = (run.stage - 1) * run.questions + run.solved
        val subject = if (run.questionSubject == "mixed") listOf("math", "english", "history")[index % 3] else run.questionSubject
        if (subject == "math") return WakeQuiz(run.question.text, run.question.answer.toString())
        val bank = if (subject == "english") english else history
        val chosen = bank.shuffled(Random(run.seed xor subject.hashCode()))[if (run.questionSubject == "mixed") index / 3 % bank.size else index % bank.size]
        return chosen.copy(choices = chosen.choices.shuffled(Random(run.seed xor index xor 0x314159)))
    }
}
