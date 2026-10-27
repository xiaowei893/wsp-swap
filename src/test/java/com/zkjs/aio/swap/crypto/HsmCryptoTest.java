package com.zkjs.aio.swap.crypto;

import com.zkjs.aio.swap.crypto.enums.CryptoMode;
import com.zkjs.aio.swap.crypto.enums.HsmVendor;
import com.zkjs.aio.swap.crypto.envelope.CipherEnvelope;
import com.zkjs.aio.swap.crypto.envelope.EncipherTagHelper;
import com.zkjs.aio.swap.crypto.factory.HsmCryptoDispatcher;
import com.zkjs.aio.swap.crypto.interceptor.CryptoMetadataCache;
import com.zkjs.aio.swap.crypto.service.HsmService;
import com.zkjs.aio.swap.crypto.service.impl.CustomHsmServiceImpl;
import com.zkjs.aio.swap.crypto.service.impl.YisuoHsmServiceImpl;
import com.zkjs.aio.swap.crypto.service.impl.YuwengHsmServiceImpl;
import com.zkjs.aio.swap.entity.SwapTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 密码机落盘加解密、动态调度与数据迁移核心单元测试
 *
 * @author wuyw
 */
public class HsmCryptoTest {

    private CustomHsmServiceImpl customHsmService;
    private YuwengHsmServiceImpl yuwengHsmService;
    private YisuoHsmServiceImpl yisuoHsmService;
    private HsmCryptoDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        customHsmService = new CustomHsmServiceImpl();
        yuwengHsmService = new YuwengHsmServiceImpl();
        yisuoHsmService = new YisuoHsmServiceImpl();
        dispatcher = new HsmCryptoDispatcher(List.of(customHsmService, yuwengHsmService, yisuoHsmService));
    }

    @Test
    @DisplayName("测试1：自描述密文信封（CipherEnvelope）打包与解析")
    void testCipherEnvelope() {
        String rawCipher = "AbC123XyZ==";
        String envelope = CipherEnvelope.pack(HsmVendor.CUSTOM, CryptoMode.FULL, rawCipher);
        assertEquals("$HSM$custom#0#AbC123XyZ==", envelope);
        assertTrue(CipherEnvelope.isEncrypted(envelope));

        CipherEnvelope.EnvelopePayload payload = CipherEnvelope.unpack(envelope);
        assertNotNull(payload);
        assertEquals(HsmVendor.CUSTOM, payload.getVendor());
        assertEquals(CryptoMode.FULL, payload.getMode());
        assertEquals(rawCipher, payload.getRawCipher());

        // 测试逐字符加密信封
        String charEnvelope = CipherEnvelope.pack(HsmVendor.YUWENG, CryptoMode.CHAR_BY_CHAR, "YW_ENC_TEST");
        assertEquals("$HSM$yuweng#1#YW_ENC_TEST", charEnvelope);
        CipherEnvelope.EnvelopePayload charPayload = CipherEnvelope.unpack(charEnvelope);
        assertNotNull(charPayload);
        assertEquals(HsmVendor.YUWENG, charPayload.getVendor());
        assertEquals(CryptoMode.CHAR_BY_CHAR, charPayload.getMode());
    }

    @Test
    @DisplayName("测试2：自定义密码机（国密软实现）整段与逐字符加解密还原")
    void testCustomHsmService() {
        String originText = "一体化交换任务_20261020";

        // 模式 0：整段加密
        String cipherFull = customHsmService.encrypt(originText, CryptoMode.FULL);
        assertNotEquals(originText, cipherFull);
        String decryptedFull = customHsmService.decrypt(cipherFull, CryptoMode.FULL);
        assertEquals(originText, decryptedFull);

        // 模式 1：紧凑型单字符拆分加密（1字符对应4位Hex，杜绝长度爆炸）
        String cipherChar = customHsmService.encrypt(originText, CryptoMode.CHAR_BY_CHAR);
        assertNotEquals(originText, cipherChar);
        assertEquals(originText.length() * 4, cipherChar.length());
        String decryptedChar = customHsmService.decrypt(cipherChar, CryptoMode.CHAR_BY_CHAR);
        assertEquals(originText, decryptedChar);
    }

    @Test
    @DisplayName("测试3：渔翁密码机与一所密码机整段与逐字符加解密还原")
    void testVendorHsmServices() {
        String text = "敏感安全数据-SECRET";

        // 渔翁密码机
        String yuwengCipher0 = yuwengHsmService.encrypt(text, CryptoMode.FULL);
        assertEquals(text, yuwengHsmService.decrypt(yuwengCipher0, CryptoMode.FULL));
        String yuwengCipher1 = yuwengHsmService.encrypt(text, CryptoMode.CHAR_BY_CHAR);
        assertEquals(text, yuwengHsmService.decrypt(yuwengCipher1, CryptoMode.CHAR_BY_CHAR));

        // 一所密码机
        String yisuoCipher0 = yisuoHsmService.encrypt(text, CryptoMode.FULL);
        assertEquals(text, yisuoHsmService.decrypt(yisuoCipher0, CryptoMode.FULL));
        String yisuoCipher1 = yisuoHsmService.encrypt(text, CryptoMode.CHAR_BY_CHAR);
        assertEquals(text, yisuoHsmService.decrypt(yisuoCipher1, CryptoMode.CHAR_BY_CHAR));
    }

    @Test
    @DisplayName("测试4：统一门面调度与纯密文加解密")
    void testDispatcherCrossVendorDecryption() {
        String plain = "内外网核心交换指令";

        // 1. 分别使用三个厂商加密，验证输出为纯 Hex 密文（无 $HSM$、YW、YS 等前缀）
        String cipherCustom = dispatcher.encrypt(plain, HsmVendor.CUSTOM, CryptoMode.FULL);
        String cipherYuweng = dispatcher.encrypt(plain, HsmVendor.YUWENG, CryptoMode.FULL);
        String cipherYisuo = dispatcher.encrypt(plain, HsmVendor.YISUO, CryptoMode.CHAR_BY_CHAR);

        assertFalse(cipherCustom.startsWith("$HSM$"));
        assertFalse(cipherYuweng.startsWith("$HSM$"));
        assertFalse(cipherYuweng.startsWith("YW"));
        assertFalse(cipherYisuo.startsWith("$HSM$"));
        assertFalse(cipherYisuo.startsWith("YS"));

        // 2. 依据各厂商及模式解密
        assertEquals(plain, dispatcher.decrypt(cipherCustom, HsmVendor.CUSTOM, CryptoMode.FULL));
        assertEquals(plain, dispatcher.decrypt(cipherYuweng, HsmVendor.YUWENG, CryptoMode.FULL));
        assertEquals(plain, dispatcher.decrypt(cipherYisuo, HsmVendor.YISUO, CryptoMode.CHAR_BY_CHAR));

        // 3. 兼容未加密历史存量明文
        assertEquals("RAW_PLAINTEXT", dispatcher.decrypt("RAW_PLAINTEXT"));
    }

    @Test
    @DisplayName("测试5：实体元数据反射缓存正确解析 @CryptoField 注解")
    void testCryptoMetadataCache() {
        List<CryptoMetadataCache.CryptoFieldMeta> metas = CryptoMetadataCache.getCryptoFields(SwapTask.class);
        assertNotNull(metas);
        assertEquals(2, metas.size());

        CryptoMetadataCache.CryptoFieldMeta taskNameMeta = metas.stream()
                .filter(m -> m.getField().getName().equals("taskName"))
                .findFirst().orElse(null);
        assertNotNull(taskNameMeta);
        assertEquals(CryptoMode.FULL, taskNameMeta.getAnnotation().mode());

        CryptoMetadataCache.CryptoFieldMeta sourceNodeMeta = metas.stream()
                .filter(m -> m.getField().getName().equals("sourceNode"))
                .findFirst().orElse(null);
        assertNotNull(sourceNodeMeta);
        assertEquals(CryptoMode.CHAR_BY_CHAR, sourceNodeMeta.getAnnotation().mode());
    }

    @Test
    @DisplayName("测试6：密码机厂商切换数据清洗平滑重密与标签更新仿真流程")
    void testVendorMigrationSimulation() {
        // 模拟存量数据（原使用 custom 厂商加密，并记录标签）
        String originalData = "核心高密节点A";
        String oldCipher = dispatcher.encrypt(originalData, HsmVendor.CUSTOM, CryptoMode.FULL);
        String oldTag = EncipherTagHelper.buildTag(List.of(
                new EncipherTagHelper.TagItem("taskName", "custom", 0),
                new EncipherTagHelper.TagItem("sourceNode", EncipherTagHelper.VENDOR_PLAINTEXT, 0)
        ));

        // 模拟迁移流程：检测到配置切换为 yuweng
        HsmVendor oldVendor = HsmVendor.CUSTOM;
        HsmVendor newVendor = HsmVendor.YUWENG;

        HsmService oldService = dispatcher.getService(oldVendor);
        HsmService newService = dispatcher.getService(newVendor);

        // 1. 旧厂商解密纯密文
        String decryptedPlain = oldService.decrypt(oldCipher, CryptoMode.FULL);
        assertEquals(originalData, decryptedPlain);

        // 2. 新厂商重新加密
        String newCipher = newService.encrypt(decryptedPlain, CryptoMode.FULL);
        assertFalse(newCipher.startsWith("$HSM$"));
        assertFalse(newCipher.startsWith("YW"));

        // 3. 更新标签，保留 plaintext
        var tagMap = EncipherTagHelper.parseTag(oldTag);
        List<EncipherTagHelper.TagItem> newTagItems = List.of(
                new EncipherTagHelper.TagItem("taskName", newVendor.getCode(), 0),
                tagMap.get("sourceNode")
        );
        String newTag = EncipherTagHelper.buildTag(newTagItems);
        assertEquals("taskName:yuweng:0|sourceNode:plaintext:0", newTag);

        // 4. 验证新密文可通过新厂商成功解密
        assertEquals(originalData, newService.decrypt(newCipher, CryptoMode.FULL));
    }

    @Test
    @DisplayName("测试7：加密标签（EncipherTagHelper）组装、解析与 plaintext 原文识别")
    void testEncipherTagHelper() {
        // 1. 测试明密文混合标签组装：taskName 为 custom 加密，sourceNode 为 plaintext 原文
        List<EncipherTagHelper.TagItem> items = List.of(
                new EncipherTagHelper.TagItem("taskName", "custom", 0),
                new EncipherTagHelper.TagItem("sourceNode", EncipherTagHelper.VENDOR_PLAINTEXT, 0)
        );
        String tag = EncipherTagHelper.buildTag(items);
        assertEquals("taskName:custom:0|sourceNode:plaintext:0", tag);

        // 2. 解析标签
        var tagMap = EncipherTagHelper.parseTag(tag);
        assertEquals(2, tagMap.size());

        EncipherTagHelper.TagItem taskNameItem = tagMap.get("taskName");
        assertNotNull(taskNameItem);
        assertEquals("custom", taskNameItem.vendor());
        assertEquals(0, taskNameItem.mode());
        assertTrue(taskNameItem.isEncrypted());

        EncipherTagHelper.TagItem sourceNodeItem = tagMap.get("sourceNode");
        assertNotNull(sourceNodeItem);
        assertEquals("plaintext", sourceNodeItem.vendor());
        assertEquals(0, sourceNodeItem.mode());
        assertFalse(sourceNodeItem.isEncrypted()); // 识别为原文未加密
    }

    @Test
    @DisplayName("测试8：@IntegrityField 顺序属性有序拼接与签名计算")
    void testIntegrityAnnotationAndOrdering() {
        SwapTask task = SwapTask.builder()
                .taskCode("TASK_ORDER_001")
                .taskName("顺序敏感任务")
                .sourceNode("NODE_A")
                .targetNode("NODE_B")
                .status(1)
                .build();

        // 1. 验证根据 @IntegrityField 的 order 属性升序拼装
        // taskCode (order=1) |#| taskName (order=2) |#| sourceNode (order=3) |#| targetNode (order=4) |#| status (order=5)
        String plain = com.zkjs.aio.swap.crypto.envelope.IntegrityHelper.buildPlainString(task);
        assertEquals("TASK_ORDER_001|#|顺序敏感任务|#|NODE_A|#|NODE_B|#|1", plain);

        // 2. 使用当前厂商 (custom) 计算签名
        String sign = dispatcher.sign(plain);
        assertNotNull(sign);
        assertEquals(64, sign.length()); // SM3 输出 64 位纯 Hex

        // 3. 验证未被篡改时验签通过
        assertTrue(dispatcher.verify(plain, sign));

        // 4. 模拟数据被篡改（将 status 从 1 改成 2）
        task.setStatus(2);
        String tamperedPlain = com.zkjs.aio.swap.crypto.envelope.IntegrityHelper.buildPlainString(task);
        assertFalse(dispatcher.verify(tamperedPlain, sign)); // 防篡改拦截成功！
    }

    @Test
    @DisplayName("测试9：不同密码厂商统一签名算法与验签机制")
    void testVendorSignatureAndVerification() {
        String testData = "TASK_CODE_99|#|涉密数据同步|#|NODE_01|#|NODE_02|#|1";

        // 1. 各厂商签名生成
        String signCustom = dispatcher.sign(testData, HsmVendor.CUSTOM);
        String signYuweng = dispatcher.sign(testData, HsmVendor.YUWENG);
        String signYisuo = dispatcher.sign(testData, HsmVendor.YISUO);

        assertNotNull(signCustom);
        assertNotNull(signYuweng);
        assertNotNull(signYisuo);

        // 验证签名都是 64 位 Hex 摘要
        assertEquals(64, signCustom.length());
        assertEquals(64, signYuweng.length());
        assertEquals(64, signYisuo.length());

        // 2. 验证各厂商各自的验签正确性
        assertTrue(dispatcher.verify(testData, signCustom, HsmVendor.CUSTOM));
        assertTrue(dispatcher.verify(testData, signYuweng, HsmVendor.YUWENG));
        assertTrue(dispatcher.verify(testData, signYisuo, HsmVendor.YISUO));

        // 3. 验证跨厂商防伪造：Custom 签名不能伪造成 Yuweng 签名
        assertFalse(dispatcher.verify(testData, signCustom, HsmVendor.YUWENG));
    }
}
