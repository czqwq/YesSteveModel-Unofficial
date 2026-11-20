package com.fox.ysmu.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.model.roaming.ModelRoamingKey;
import com.google.gson.Gson;

/**
 * 服务端保存的模型设置声明快照，对应上游服务端那份已验证模型的 {@code Settings}（{@code CH} = 上游
 * {@code network/forge/ControlHandler.java:314-329} 的 {@code ServerTarget}）。
 * <p>
 * 上游服务端烘焙模型后手里就有完整的 settings，于是 {@code authorizeConfigAction} 能在转发前回答"这个
 * button/form/label 是否由该模型声明"（{@code ControlHandler.java:216-226,277-293}）。1.7.10 服务端烘模型时手里也有
 * {@link WheelDeclarations}（{@link ModelData#getWheelDeclarations()}，随 {@code RawYsmModel.RawProperties} 一路
 * 带进来），所以把它的 button→form 类型/标签名快照存进 {@code ServerModelManager.CONFIG_DECLARATIONS}，与上游同一
 * 形状。声明<b>不含 title/程序源码</b>：程序由各端从本地模型载荷现场编译，wire 上从不携带。
 *
 * <h2>两种用法</h2>
 * <ul>
 * <li>服务端：{@link #accepts(ConfigAction)} 校验一次 config 动作是否命中模型自己的声明；
 * {@link #identity()} 与 {@link #roamingKey(String)} 提供 roaming 校验所需的模型内容身份。</li>
 * <li>客户端：{@code ClientRoamingKeys}/{@code ConfigActionRuntime} 只读同一份 {@link WheelDeclarations}
 * 内容哈希，不依赖本类。</li>
 * </ul>
 *
 * <h2>持久化</h2>
 * 随 {@code ModelIndexCache.Entry} 一起写进 {@code cache/server/model_index.json}（{@link #encode()} /
 * {@link #decode(String)}）：索引命中时服务端跳过重解析，也要能重建同一份声明，否则命中与完整路径的服务端行为会
 * 分叉。见 {@code ModelIndexCache} 的 {@code INDEX_VERSION} 2。
 */
public final class ConfigDeclarations {

    private static final Gson GSON = new Gson();

    /**
     * 模型内容哈希（{@code RawProperties.sha256}），可为空串。上游由服务端 Settings proto 重算
     * config identity；移植的同一份事实就是随载荷到达的 sha256，客户端已用它算 roaming key
     * （{@code ClientModelManager.java:723-726} → {@code ClientRoamingKeys.accept}）。
     */
    public String sha256 = "";

    /** 声明顺序的 button 列表，顺序与模型 {@code ysm.json} 的 {@code extra_animation_buttons} 一致。 */
    public List<Button> buttons = new ArrayList<>();

    /** {@code extra_animation_buttons[]} 的一个条目：只保留 id 与 form 声明。 */
    public static final class Button {

        public String id = "";
        public List<Form> forms = new ArrayList<>();
    }

    /** 一个 config form：只保留类型与 label 名（顺序即 label 的声明顺序）。 */
    public static final class Form {

        public String type = "";
        public List<String> labelNames = new ArrayList<>();
    }

    /** Gson 用的无参构造；字段初始化器保证反序列化后各集合非 null（decode 仍会显式校验）。 */
    public ConfigDeclarations() {}

    /**
     * 从模型自己的声明构建快照；只保留 id/type/label 名，丢掉 title/description/程序。
     * {@code values} 为 {@code null} 时返回空声明（无按钮）。
     */
    public static ConfigDeclarations from(@Nullable WheelDeclarations.Values values) {
        ConfigDeclarations declarations = new ConfigDeclarations();
        if (values == null) {
            return declarations;
        }
        declarations.sha256 = values.sha256 == null ? "" : values.sha256;
        if (values.buttons == null) {
            return declarations;
        }
        for (RawYsmModel.ExtraAnimationButton source : values.buttons) {
            if (source == null) {
                continue;
            }
            Button button = new Button();
            button.id = source.id == null ? "" : source.id;
            if (source.forms != null) {
                for (RawYsmModel.ConfigForm sourceForm : source.forms) {
                    if (sourceForm == null) {
                        continue;
                    }
                    Form form = new Form();
                    form.type = sourceForm.type == null ? "" : sourceForm.type;
                    if (sourceForm.labels != null) {
                        form.labelNames = new ArrayList<>(sourceForm.labels.keySet());
                    }
                    button.forms.add(form);
                }
            }
            declarations.buttons.add(button);
        }
        return declarations;
    }

    /**
     * 反序列化索引里持久化的形态。损坏时抛 {@link IllegalArgumentException}，由索引把整份条目标成损坏
     * （{@code ModelIndexCache.MalformedIndexException} 的同一形状），绝不返回半份声明。
     */
    public static ConfigDeclarations decode(String json) {
        if (json == null || json.isEmpty()) {
            throw new IllegalArgumentException("Missing config declarations");
        }
        ConfigDeclarations decoded = GSON.fromJson(json, ConfigDeclarations.class);
        if (decoded == null || decoded.sha256 == null || decoded.buttons == null) {
            throw new IllegalArgumentException("Incomplete config declarations");
        }
        for (Button button : decoded.buttons) {
            if (button == null || button.id == null || button.forms == null) {
                throw new IllegalArgumentException("Incomplete config declaration button");
            }
            for (Form form : button.forms) {
                if (form == null || form.type == null || form.labelNames == null) {
                    throw new IllegalArgumentException("Incomplete config declaration form");
                }
            }
        }
        return decoded;
    }

    /** 持久化形态；{@link #decode(String)} 读回。 */
    public String encode() {
        return GSON.toJson(this);
    }

    /**
     * 上游 {@code validCatalogAction}（{@code ControlHandler.java:277-293}）的移植：在声明里找同 id 的 button、
     * form 索引在界内、kind 与 form 类型匹配、radio 的 label 顺序索引与名字逐字相等。
     */
    public boolean accepts(@Nullable ConfigAction action) {
        if (action == null || !action.valid()) {
            return false;
        }
        for (Button button : buttons) {
            if (button == null || !Objects.equals(button.id, action.buttonId)) {
                continue;
            }
            if (action.formIndex < 0 || action.formIndex >= button.forms.size()) {
                return false;
            }
            Form form = button.forms.get(action.formIndex);
            if (form == null) {
                return false;
            }
            switch (action.kind) {
                case ConfigAction.KIND_BOOL:
                    return "checkbox".equals(form.type);
                case ConfigAction.KIND_F32:
                    return "range".equals(form.type);
                case ConfigAction.KIND_LABEL:
                    return "radio".equals(form.type) && action.labelIndex >= 0
                        && action.labelIndex < form.labelNames.size()
                        && Objects.equals(form.labelNames.get(action.labelIndex), action.labelName);
                default:
                    return false;
            }
        }
        return false;
    }

    /**
     * 该模型 roaming 命名空间的 key。上游 {@code PSH}（{@code network/forge/PlayerStateHandler.java:271-286}）
     * 用 {@code capability.getModelId().roamingHash()} 校验上报；移植两侧都从内容哈希派生
     * （{@link ModelRoamingKey}），{@code modelId} 只在哈希不可用时作兜底，所以调用方必须传与客户端
     * {@code ClientRoamingKeys.accept} 完全相同的 id 拼写（{@code ResourceLocation#toString()}）。
     */
    public int roamingKey(@Nullable String modelId) {
        return ModelRoamingKey.of(sha256, modelId);
    }

    /** 模型内容身份；没有 sha256 时为空串，报文与接收端据此跳过身份比对（上游 identity 缺失的等价形态）。 */
    public String identity() {
        return sha256 == null ? "" : sha256;
    }
}
