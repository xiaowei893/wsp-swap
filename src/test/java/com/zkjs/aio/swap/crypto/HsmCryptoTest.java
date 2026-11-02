package com.zkjs.aio.swap.crypto;

import com.zkjs.aio.crypto.config.GmProperties;
import com.zkjs.aio.crypto.core.CryptoDispatcher;
import com.zkjs.aio.crypto.core.CryptoVoucherManager;
import com.zkjs.aio.crypto.core.CryptoVoucherStorage;
import com.zkjs.aio.crypto.core.VendorMapper;
import com.zkjs.aio.crypto.enums.CryptoMode;
import com.zkjs.aio.crypto.envelope.CryptoEntityMetadata;
import com.zkjs.aio.crypto.envelope.EncipherTagHelper;
import com.zkjs.aio.swap.entity.SwapTask;
import com.zkjs.gm.pool.PwdPoolClient;
import com.zkjs.gm.pool.enums.PwdFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 国密加解密与防篡改组件核心功能单元测试
 * 针对 aio-spring-boot-starter-crypto 官方组件进行调度、元数据解析、标签生成与软国密验签核验
 *
 * @author wuyw
 */
public class HsmCryptoTest {

    private CryptoDispatcher dispatcher;
    private CryptoVoucherManager voucherManager;
    private PwdPoolClient pwdPoolClient;

    @BeforeEach
    void setUp() {
        GmProperties gmProperties = new GmProperties();
        gmProperties.setDefaultPwdFactory("custom");
        pwdPoolClient = gmProperties.buildPwdPoolClient();

        Map<String, String> inMemoryDb = new HashMap<>();
        CryptoVoucherStorage storage = new CryptoVoucherStorage() {
            @Override
            public String loadVoucherFromDb(String factory) {
                return inMemoryDb.get(factory.toUpperCase());
            }

            @Override
            public void saveVoucherToDb(String factory, String voucher) {
                inMemoryDb.put(factory.toUpperCase(), voucher);
            }
        };

        voucherManager = new CryptoVoucherManager(storage);

        // 初始化软国密 CUSTOM 密钥凭证（按照官方 SDK 规范拼接 32 位 Hex SM4 密钥与 SM2 密钥对）
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        String sm4Key = sb.toString();

        Map<String, String> sm2Keys = pwdPoolClient.getCustomSm2PubAndPriKeys();
        String priKey = sm2Keys != null ? sm2Keys.get("PRIVATE_KEY") : "";
        String pubKey = sm2Keys != null ? sm2Keys.get("PUBLIC_KEY") : "";
        String raw = sm4Key + "-" + priKey + "-" + pubKey;
        String voucher = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        voucherManager.saveAndCacheVoucher(PwdFactory.CUSTOM, voucher);

        dispatcher = new CryptoDispatcher(pwdPoolClient, voucherManager, gmProperties);
    }

    @Test
    @DisplayName("测试1：实体元数据反射缓存正确解析 @CryptoField, @IntegrityField 及约定列")
    void testEntityMetadataParsing() {
        CryptoEntityMetadata.EntityMeta meta = CryptoEntityMetadata.get(SwapTask.class);
        assertNotNull(meta);
        assertEquals("swap_task", meta.getTableName());
        assertTrue(meta.hasCrypto());
        assertTrue(meta.hasIntegrity());

        // 验证加密字段
        assertEquals(2, meta.getCryptoFields().size());
        assertTrue(meta.getCryptoFields().stream().anyMatch(f ->
                f.field().getName().equals("taskName") && f.ann().mode() == CryptoMode.FULL));
        assertTrue(meta.getCryptoFields().stream().anyMatch(f ->
                f.field().getName().equals("sourceNode") && f.ann().mode() == CryptoMode.CHAR_BY_CHAR));

        // 验证防篡改字段排序 (1~5)
        assertEquals(5, meta.getIntegrityFields().size());
        assertEquals("taskCode", meta.getIntegrityFields().get(0).field().getName());
        assertEquals("taskName", meta.getIntegrityFields().get(1).field().getName());
        assertEquals("sourceNode", meta.getIntegrityFields().get(2).field().getName());
        assertEquals("targetNode", meta.getIntegrityFields().get(3).field().getName());
        assertEquals("status", meta.getIntegrityFields().get(4).field().getName());

        // 验证约定状态与签名列
        assertNotNull(meta.getTagField());
        assertEquals("encipher_tag", meta.getTagColumn());
        assertNotNull(meta.getSignField());
        assertEquals("sign_value", meta.getSignColumn());
        assertNotNull(meta.getStatusField());
        assertEquals("data_tampered", meta.getStatusColumn());
    }

    @Test
    @DisplayName("测试2：软国密 (CUSTOM) SM4 整段与逐字符拆分加密及解密还原")
    void testCustomSm4EncryptionAndDecryption() {
        String originalText = "一体化交换任务_20261029";

        // 1. 整段加密 (FULL)
        String cipherFull = dispatcher.encrypt(originalText, PwdFactory.CUSTOM, CryptoMode.FULL);
        assertNotNull(cipherFull);
        assertNotEquals(originalText, cipherFull);
        String decryptedFull = dispatcher.decrypt(cipherFull, PwdFactory.CUSTOM, CryptoMode.FULL);
        assertEquals(originalText, decryptedFull);

        // 2. 逐字符拆分加密 (CHAR_BY_CHAR，支持密文模糊检索)
        String cipherChar = dispatcher.encrypt(originalText, PwdFactory.CUSTOM, CryptoMode.CHAR_BY_CHAR);
        assertNotNull(cipherChar);
        assertNotEquals(originalText, cipherChar);
        String decryptedChar = dispatcher.decrypt(cipherChar, PwdFactory.CUSTOM, CryptoMode.CHAR_BY_CHAR);
        assertEquals(originalText, decryptedChar);

        // 3. 验证逐字符加密的片段匹配特性
        String keyword = "交换任务";
        String keywordCipher = dispatcher.encrypt(keyword, PwdFactory.CUSTOM, CryptoMode.CHAR_BY_CHAR);
        assertTrue(cipherChar.contains(keywordCipher), "逐字符加密后的全密文必须包含子串明文对应的密文片段");
    }

    @Test
    @DisplayName("测试3：国密 SM3-HMAC 数据防伪签名与验签机制")
    void testSm3HmacSignAndVerify() {
        String payload = "TASK_20261029_001|#|核心涉密同步任务|#|NODE_01|#|NODE_02|#|1";

        // 1. 生成防篡改签名
        String signValue = dispatcher.sign(payload);
        assertNotNull(signValue);
        assertEquals(64, signValue.length(), "SM3 输出应为 64 位纯 Hex 散列摘要");

        // 2. 正常报文验签通过
        assertTrue(dispatcher.verify(payload, signValue));

        // 3. 模拟数据被篡改（如状态由 1 改为 2）
        String tamperedPayload = "TASK_20261029_001|#|核心涉密同步任务|#|NODE_01|#|NODE_02|#|2";
        assertFalse(dispatcher.verify(tamperedPayload, signValue), "被篡改数据验签必须失败，阻断安全逃逸");
    }

    @Test
    @DisplayName("测试4：加密标签工具类 (EncipherTagHelper) 组装与解析")
    void testEncipherTagHelper() {
        List<EncipherTagHelper.TagItem> items = List.of(
                new EncipherTagHelper.TagItem("taskName", "custom", 0),
                new EncipherTagHelper.TagItem("sourceNode", "custom", 1)
        );

        String tag = EncipherTagHelper.buildTag(items);
        assertNotNull(tag);
        assertTrue(tag.contains("taskName:custom:0"));
        assertTrue(tag.contains("sourceNode:custom:1"));

        Map<String, EncipherTagHelper.TagItem> map = EncipherTagHelper.parseTag(tag);
        assertEquals(2, map.size());
        assertEquals("custom", map.get("taskName").vendor());
        assertEquals(0, map.get("taskName").mode());
        assertEquals("custom", map.get("sourceNode").vendor());
        assertEquals(1, map.get("sourceNode").mode());
    }

    @Test
    @DisplayName("测试5：厂商代码转换工具类 (VendorMapper)")
    void testVendorMapper() {
        assertEquals(PwdFactory.CUSTOM, VendorMapper.toPwdFactory("custom"));
        assertEquals(PwdFactory.FISEC, VendorMapper.toPwdFactory("fisec"));
        assertEquals(PwdFactory.GAYS, VendorMapper.toPwdFactory("gays"));

        assertEquals("custom", VendorMapper.toStandardCode(PwdFactory.CUSTOM));
        assertEquals("fisec", VendorMapper.toStandardCode(PwdFactory.FISEC));
        assertEquals("gays", VendorMapper.toStandardCode(PwdFactory.GAYS));
    }
}
