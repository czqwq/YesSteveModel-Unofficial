package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

import software.bernie.geckolib3.core.molang.MolangPhysicsRuntime;

/**
 * {@code ysm.bone_pivot_abs('BoneName').x/y/z} 的 mclib 实现。
 *
 * <p>返回骨骼旋转中心在模型空间的绝对位置（模型单位，16 单位 = 1 格），
 * 沿 {@code GeoBone} 父链应用每个骨的完整变换（平移/枢轴/旋转/缩放，
 * 与渲染 {@code MatrixStack.transformBone} 一致），语义对齐 OpenYSM
 * 的 {@code bone_pivot_abs}（世界/模型空间绝对枢轴，含父级旋转与动画姿态）。</p>
 *
 * <p>表达式中的 {@code .x/.y/.z} 后缀由 {@code MolangParser.rewriteVectorFunction}
 * 重写为 {@code ysm.bone_pivot_abs_x/y/z(...)} 三个注册名，本类按函数名末字符
 * 返回对应轴。无后缀的向量形式（{@code math.max(ysm.bone_pivot_abs('Root'),…)} 这种
 * 重写覆盖不到的位置）由 {@code MolangParser.doCoreRemaps} 直接注册的
 * {@code ysm.bone_pivot_abs} 落到本类，末字符不是 a/y/z 时按 x 轴取值而非拒绝。</p>
 */
public class BonePivotAbsFunction extends Function {

    public BonePivotAbsFunction(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    public double get() {
        int nameId = (int) getArg(0);
        return MolangPhysicsRuntime.bonePivot(nameId, axis());
    }

    /** 名字末字符给出轴；未命名的向量形式（末字符是 {@code t}/{@code s}/{@code e}）回落到 x，语义与
     *  {@code BoneRotation}/{@code BonePosition}/{@code BoneScale} 的 {@code axis()} 一致。 */
    private char axis() {
        if (this.name == null || this.name.isEmpty()) {
            return 'x';
        }
        char axis = this.name.charAt(this.name.length() - 1);
        return axis == 'x' || axis == 'y' || axis == 'z' ? axis : 'x';
    }
}
