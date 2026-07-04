package lab.agentharness.thinking;

/**
 * 慢思考模块，在模型行动前注入边界检查和审批意识。
 */
public final class ThinkingModule {
    public String beforeActionInstruction() {
        return "行动前先复述目标、检查边界、确认是否需要工具；遇到高危操作必须请求审批。";
    }
}
