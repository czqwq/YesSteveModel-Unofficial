package com.fox.ysmu.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.gui.ModelConfigRegistry;
import com.fox.ysmu.client.roaming.ClientRoamingKeys;
import com.fox.ysmu.model.format.Type;
import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.model.roaming.ModelRoamingKey;
import com.fox.ysmu.util.Md5Utils;

class LegacyWheelDeclarationsTest {

    private static final ResourceLocation ID = new ResourceLocation("ysmu", "wheel_transport_probe");

    @AfterEach
    void clear() {
        ClientModelManager.registerAll(payload(WheelDeclarations.empty()));
        ModelConfigRegistry.clear();
        ClientRoamingKeys.clear();
    }

    private static ModelData payload(WheelDeclarations declarations) {
        return new ModelData(ID.getResourcePath(), Type.FOLDER, Collections.emptyMap(), Collections.emptyMap(),
            Collections.emptyMap(), Collections.emptyMap(), declarations);
    }

    private static RawYsmModel.RawProperties properties() {
        RawYsmModel.RawProperties p = new RawYsmModel.RawProperties();
        p.sha256 = "123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123";
        p.extraAnimations.put("Wave", "Hello");
        p.extraAnimations.put("#page", "More");
        p.extraAnimations.put("Settings", "#settings");
        RawYsmModel.ExtraAnimationClassify page = new RawYsmModel.ExtraAnimationClassify();
        page.id = "page";
        page.extras.put("Dance", "Dance label");
        page.extras.put("#return", "Back");
        p.extraAnimationClassifies.add(page);
        RawYsmModel.ExtraAnimationButton button = new RawYsmModel.ExtraAnimationButton();
        button.id = "settings";
        button.name = "Model settings";
        button.description = "Panel description";
        for (String type : List.of("range", "checkbox", "radio")) {
            RawYsmModel.ConfigForm form = new RawYsmModel.ConfigForm();
            form.type = type;
            form.title = type + " title";
            form.description = type + " description";
            form.defaultValue = "v.roaming." + type;
            form.min = -1;
            form.max = 2;
            form.step = 0.25f;
            form.labels.put("2", "Second");
            form.labels.put("1", "First");
            button.forms.add(form);
        }
        p.extraAnimationButtons.add(button);
        return p;
    }

    @Test
    void encryptedRoundTripRegistersOrderedPairsAllFormsAndNamespace() throws Exception {
        RawYsmModel.RawProperties p = properties();
        WheelDeclarations snapshot = WheelDeclarations.from(p);
        p.extraAnimations.clear();
        p.extraAnimationButtons.get(0).forms.get(0).title = "mutated";
        ModelData before = payload(snapshot);
        EncryptTools.createRandomPassword();
        byte[] password = EncryptTools.writePassword();
        byte[] uuid = new byte[16];
        byte[] container = EncryptTools.assembleEncryptModels(before);
        ModelData after = EncryptTools.decryptModel(uuid, EncryptTools.encryptPassword(uuid, password), container);
        assertNotNull(after);
        ClientModelManager.registerAll(after);
        assertEquals(List.of("Wave", "#page", "Settings"), new ArrayList<>(ModelConfigRegistry.wheelEntriesFor(ID).keySet()));
        assertEquals("#settings", ModelConfigRegistry.wheelEntriesFor(ID).get("Settings"));
        assertEquals(List.of("Dance", "#return"), new ArrayList<>(ModelConfigRegistry.classifyFor(ID, "page").extras.keySet()));
        List<RawYsmModel.ConfigForm> forms = ModelConfigRegistry.buttonFor(ID, "settings").forms;
        assertEquals(List.of("range", "checkbox", "radio"), forms.stream().map(f -> f.type).collect(java.util.stream.Collectors.toList()));
        assertEquals("range title", forms.get(0).title);
        assertEquals("range description", forms.get(0).description);
        assertEquals("v.roaming.range", forms.get(0).defaultValue);
        assertEquals(-1, forms.get(0).min);
        assertEquals(2, forms.get(0).max);
        assertEquals(0.25f, forms.get(0).step);
        assertEquals(List.of("2", "1"), new ArrayList<>(forms.get(0).labels.keySet()));
        assertEquals(ModelRoamingKey.of(properties().sha256, ID.toString()), ClientRoamingKeys.keyFor(ID));
        assertTrue(after.getGuiImages().isEmpty());
        assertTrue(after.getModel().isEmpty());
        snapshot.values().buttons.clear();
        assertEquals(1, snapshot.values().buttons.size(), "getter must not expose snapshot's mutable internals");
        assertArrayEquals(password, EncryptTools.writePassword());
    }

    @Test
    void declarationOnlyChangesReRegisterAndEmptyPayloadClearsOldState() throws Exception {
        RawYsmModel.RawProperties p = properties();
        ModelData first = payload(WheelDeclarations.from(p));
        ClientModelManager.registerAll(first);
        EncryptTools.createRandomPassword();
        String oldHash = Md5Utils.md5Hex(EncryptTools.assembleEncryptModels(first));
        p.extraAnimations.put("Wave", "Changed");
        ClientModelManager.registerAll(payload(WheelDeclarations.from(p)));
        assertEquals("Changed", ModelConfigRegistry.wheelEntriesFor(ID).get("Wave"));
        // Only the namespace changes next: fingerprinting must still install its new key.
        p.sha256 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210fedcba";
        ModelData second = payload(WheelDeclarations.from(p));
        assertNotEquals(oldHash, Md5Utils.md5Hex(EncryptTools.assembleEncryptModels(second)));
        ClientModelManager.registerAll(second);
        assertEquals("Changed", ModelConfigRegistry.wheelEntriesFor(ID).get("Wave"));
        assertEquals(ModelRoamingKey.of(p.sha256, ID.toString()), ClientRoamingKeys.keyFor(ID));
        ClientModelManager.registerAll(payload(WheelDeclarations.empty()));
        assertTrue(ModelConfigRegistry.wheelEntriesFor(ID).isEmpty());
        assertTrue(ModelConfigRegistry.buttonsFor(ID).isEmpty());
        assertTrue(ModelConfigRegistry.classifiesFor(ID).isEmpty());
        assertEquals(ModelRoamingKey.of(null, ID.toString()), ClientRoamingKeys.keyFor(ID));
    }
}
