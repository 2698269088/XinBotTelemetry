package top.mcocet.i18n;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 轻量多语言支持,语言文件与 Xinbot 核心同款 .lang 格式(UTF-8):
 *
 * <pre>
 *   key=value        # 每行一条,按第一个 '=' 切分
 *   # 注释行         # '#' 开头与空行忽略
 * </pre>
 *
 * <p>支持 zh_cn(简体中文,默认)、zh_tw(繁体中文)、en_us(英文)。
 * 加载方式与 Xinbot LangManager 一致:先加载 en_us.lang 作为兜底基座,
 * 再用目标语言覆盖同名 key;缺失的 key 最终直接显示 key 本身。</p>
 *
 * <p>带参数的消息用 {@link String#format} 风格占位(%s %d),与 Xinbot 核心一致;
 * 页面模板专用的 {timeout} {total} {time} 等占位不经此格式化。</p>
 */
public final class I18n {

    public static final String LANG_ZH_CN = "zh_cn";
    public static final String LANG_ZH_TW = "zh_tw";
    public static final String LANG_EN_US = "en_us";

    /** en_us 为兜底基座语言,其它语言缺 key 时回落到它 */
    public static final String FALLBACK_LANGUAGE = LANG_EN_US;

    private static final Map<String, String> EN_US = load(FALLBACK_LANGUAGE);
    private static final Map<String, String> ZH_CN = merged(load(LANG_ZH_CN));
    private static final Map<String, String> ZH_TW = merged(load(LANG_ZH_TW));

    private static volatile String current = LANG_ZH_CN;
    private static volatile Map<String, String> currentTable = ZH_CN;

    private I18n() {
    }

    /** 是否支持该语言代码 */
    public static boolean supports(String lang) {
        return LANG_ZH_CN.equals(lang) || LANG_ZH_TW.equals(lang) || LANG_EN_US.equals(lang);
    }

    /** 切换到指定语言;不支持的语言抛 IOException */
    public static void init(String lang) throws IOException {
        if (!supports(lang)) {
            throw new IOException("Unsupported language: " + lang);
        }
        current = lang;
        currentTable = switch (lang) {
            case LANG_ZH_TW -> ZH_TW;
            case LANG_EN_US -> EN_US;
            default -> ZH_CN;
        };
    }

    /** 当前语言代码 */
    public static String lang() {
        return current;
    }

    /** 取当前语言文案;缺失时回落到 en_us,再缺失则直接显示 key */
    public static String get(String key) {
        return currentTable.getOrDefault(key, key);
    }

    /** 取当前语言文案并格式化(与 Xinbot 核心一致,String.format 风格) */
    public static String get(String key, Object... args) {
        String template = get(key);
        try {
            return String.format(template, args);
        } catch (Exception e) {
            return template;
        }
    }

    /** 目标语言叠加在 en_us 基座之上 */
    private static Map<String, String> merged(Map<String, String> target) {
        Map<String, String> merged = new HashMap<>(EN_US);
        merged.putAll(target);
        return merged;
    }

    /** 解析 lang/&lt;code&gt;.lang,解析规则与 Xinbot 核心 parseLangStream 一致 */
    private static Map<String, String> load(String lang) {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = I18n.class.getResourceAsStream("/lang/" + lang + ".lang")) {
            if (in == null) {
                return map;
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int equalsIndex = line.indexOf('=');
                if (equalsIndex <= 0) {
                    continue;
                }
                String key = line.substring(0, equalsIndex).trim();
                String value = line.substring(equalsIndex + 1).trim();
                if (!key.isEmpty()) {
                    // 允许 \n \t 转义,与 Xinbot 核心一致
                    value = value.replace("\\n", "\n").replace("\\t", "\t");
                    map.put(key, value);
                }
            }
        } catch (IOException ignored) {
            // 语言文件缺失时该语言为空,自动回落到 en_us
        }
        return map;
    }
}
