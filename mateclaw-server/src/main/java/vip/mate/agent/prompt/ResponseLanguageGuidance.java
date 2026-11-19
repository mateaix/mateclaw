package vip.mate.agent.prompt;

/** Shared language preference for user-visible model output, not a provider-enforced setting. */
public final class ResponseLanguageGuidance {
    private ResponseLanguageGuidance() { }

    public static final String PROMPT = """


            ## 语言一致性（用户可见输出）

            - 优先遵循用户明确指定的输出语言；未指定时跟随当前原始用户问题的语言，即用户语言。
            - 用户用中文提问且未指定其他语言时，可见思考说明、过程提示和最终答复都使用简体中文，而非仅将最终答复改成中文。
            - 用户要求英文或其他语言时，上述可见内容使用相应语言，不要一律改成中文。
            - 工具返回、内部步骤描述或历史内容的语言不改变用户的语言要求；代码、工具名、参数名、API 字段和专有名词保留原文。
            """;
}
