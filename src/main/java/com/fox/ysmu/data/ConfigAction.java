package com.fox.ysmu.data;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.fox.ysmu.model.resource.pojo.RawYsmModel;

/**
 * 一次「模型设置」动作的语义路由与类型化值，<b>绝不含 MoLang 源码</b>。
 * <p>
 * 上游的 config form 在读模型时就编译成类型化 program（{@code format/parser/ModelInfoBuilder.java:152-177}），
 * wire 上只传 button/form 的语义路由与类型化 payload：C2S {@code SubmitConfigActionRequest} 与 S2C
 * {@code ConfigActionEvent} 共用同一个 {@code ConfigActionProto}（{@code tmp/YesSteveModel-dev-1.20} 的
 * {@code network/protocol/control.proto:44-71}；协议 README
 * {@code docs/standards/protocol-v1/README.md:104-112} 明写"执行程序不进 wire"）。本类是那个 payload 在 1.7.10
 * 侧的对应物，由 task-3 的 {@code network/message/C2SConfigAction}/{@code S2CConfigAction} 承载。
 *
 * <h2>字段与 bound 的上游出处</h2>
 * <ul>
 * <li>{@link #MAX_BUTTON_ID_LENGTH} = {@code ExpressionProtocol.MAX_BUTTON_ID_LENGTH}
 * （{@code network/protocol/ExpressionProtocol.java:22}）；</li>
 * <li>{@link #MAX_LABEL_NAME_LENGTH} = {@code ExpressionProtocol.MAX_LABEL_NAME_LENGTH}（同 :23）；</li>
 * <li>{@link #MAX_FORM_INDEX} = {@code ConfigActionRoute.MAX_FORM_INDEX = 16383}
 * （{@code info/roulette/forms/ConfigActionRoute.java:8}），因此 wire 上的 unsigned short 上限里前 14 位才是合法
 * form 索引；</li>
 * <li>{@link #valid()} 对应 {@code ControlHandler.validWireAction}
 * （{@code network/forge/ControlHandler.java:262-275}）：bool 不得带 label、F32 必须有限且不得带 label、
 * label 必须同时有界 index 与非空 name。</li>
 * </ul>
 */
public final class ConfigAction {

    /** 上游 {@code checkbox} form 的类型字节；{@code ControlHandler.java:283-284}。 */
    public static final byte KIND_BOOL = 1;
    /** 上游 {@code range} form 的类型字节；{@code ControlHandler.java:285-286}。 */
    public static final byte KIND_F32 = 2;
    /** 上游 {@code radio} form 的类型字节；{@code ControlHandler.java:287-290}。 */
    public static final byte KIND_LABEL = 3;

    /** 上游 {@code ExpressionProtocol.MAX_BUTTON_ID_LENGTH}（:22）。 */
    public static final int MAX_BUTTON_ID_LENGTH = 128;
    /** 上游 {@code ExpressionProtocol.MAX_LABEL_NAME_LENGTH}（:23）。 */
    public static final int MAX_LABEL_NAME_LENGTH = 128;
    /** 上游 {@code ConfigActionRoute.MAX_FORM_INDEX}（:8）。 */
    public static final int MAX_FORM_INDEX = 16_383;

    /** 上游 {@code ConfigActionProto.buttonId}；{@link #valid()} 判它非空且不超过 {@link #MAX_BUTTON_ID_LENGTH}。 */
    public final String buttonId;
    /** 该 button 内 config form 的声明顺序索引。 */
    public final int formIndex;
    /** {@link #KIND_BOOL} / {@link #KIND_F32} / {@link #KIND_LABEL}。 */
    public final byte kind;
    /** {@link #KIND_BOOL} 的值；其余 kind 恒为 {@code false}。 */
    public final boolean boolValue;
    /** {@link #KIND_F32} 的值；其余 kind 恒为 {@code 0}。 */
    public final float floatValue;
    /** {@link #KIND_LABEL} 的声明顺序索引；其余 kind 恒为 {@code -1}。 */
    public final int labelIndex;
    /** {@link #KIND_LABEL} 的 label 名，服务端用它逐字比对模型自己的声明；其余 kind 为 {@code null}。 */
    @Nullable
    public final String labelName;

    private ConfigAction(String buttonId, int formIndex, byte kind, boolean boolValue, float floatValue,
        int labelIndex, @Nullable String labelName) {
        this.buttonId = buttonId;
        this.formIndex = formIndex;
        this.kind = kind;
        this.boolValue = boolValue;
        this.floatValue = floatValue;
        this.labelIndex = labelIndex;
        this.labelName = labelName;
    }

    /** checkbox 动作。 */
    public static ConfigAction boolAction(@Nullable String buttonId, int formIndex, boolean value) {
        return new ConfigAction(buttonId, formIndex, KIND_BOOL, value, 0.0f, -1, null);
    }

    /** range 动作。 */
    public static ConfigAction floatAction(@Nullable String buttonId, int formIndex, float value) {
        return new ConfigAction(buttonId, formIndex, KIND_F32, false, value, -1, null);
    }

    /** radio 动作；{@code labelIndex} 是 label 在 form 声明里的顺序索引，{@code labelName} 是它的名字。 */
    public static ConfigAction labelAction(@Nullable String buttonId, int formIndex, @Nullable String labelName,
        int labelIndex) {
        return new ConfigAction(buttonId, formIndex, KIND_LABEL, false, 0.0f, labelIndex, labelName);
    }

    /**
     * 结构校验，对应上游 {@code ControlHandler.validWireAction}（{@code :262-275}）。
     * <p>
     * 上游比较 Java {@code String.length()} 与 {@code isBlank()}；1.7.10 侧用同样的口径（char 数 + trim 后非空），
     * 不把 UTF-8 字节数混进来——字节长度是 decoder 的分配上限，不是语义上限。
     */
    public boolean valid() {
        if (buttonId == null || buttonId.trim()
            .isEmpty() || buttonId.length() > MAX_BUTTON_ID_LENGTH) {
            return false;
        }
        if (formIndex < 0 || formIndex > MAX_FORM_INDEX) {
            return false;
        }
        switch (kind) {
            case KIND_BOOL:
                return labelName == null || labelName.isEmpty();
            case KIND_F32:
                return (labelName == null || labelName.isEmpty()) && Float.isFinite(floatValue);
            case KIND_LABEL:
                return labelIndex >= 0 && labelIndex <= MAX_FORM_INDEX && labelName != null && !labelName.isEmpty()
                    && labelName.length() <= MAX_LABEL_NAME_LENGTH;
            default:
                return false;
        }
    }

    /**
     * 单个 form 的校验，对应上游 {@code validCatalogAction} 的单 form 部分
     * （{@code ControlHandler.java:277-293}）：bool ↔ {@code checkbox}、f32 ↔ {@code range}、
     * label ↔ {@code radio} 且 {@code labels[index].name} 逐字相等。
     * <p>
     * 这里读的是<b>本地模型载荷</b>里的同一个 form（服务端读服务端声明快照、客户端读本地载荷），所以 wire 上从不
     * 需要携带 form 类型或 label 表。
     */
    public boolean matches(@Nullable RawYsmModel.ConfigForm form) {
        if (form == null) {
            return false;
        }
        switch (kind) {
            case KIND_BOOL:
                return "checkbox".equals(form.type);
            case KIND_F32:
                return "range".equals(form.type);
            case KIND_LABEL: {
                if (!"radio".equals(form.type) || form.labels == null) {
                    return false;
                }
                List<String> names = new ArrayList<>(form.labels.keySet());
                return labelIndex >= 0 && labelIndex < names.size() && names.get(labelIndex)
                    .equals(labelName);
            }
            default:
                return false;
        }
    }
}
