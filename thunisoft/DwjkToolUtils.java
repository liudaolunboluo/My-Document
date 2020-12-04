package com.thunisoft.zgfy.dwjk.utils;

import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.springframework.util.Base64Utils;

import com.google.api.client.util.Lists;
import com.google.api.client.util.Maps;
import com.thunisoft.zgfy.dwjk.consts.Const;
import com.thunisoft.zgfy.sdk.commons.consts.YwlxEnum;

import lombok.extern.slf4j.Slf4j;

/**
 *
 * ClassName: DwjkToolUtils
 *
 * @Description: 一些常用的工具的工具类（我到底在说啥）
 * @author zhangyunfan
 * @version 1.0
 *
 * @date 2020年2月28日
 */
@Slf4j
public final class DwjkToolUtils {

    /** T3C规定的一次最大查询次数 */
    public static final Integer LIMIT_MAX = 1000;

    /** 时间格式 年月日 */
    private static final DateTimeFormatter DATETIMEFORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 时间格式 年月日 时分秒 */
    private static final DateTimeFormatter DATETIMEFORMATTER_HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 案件编号补齐位 */
    private static final String BH_COMPLEMENT = "00000000000_";

    private static final String DECODEING = "UTF-8";

    /** 案件类别代码和案件类别标识的key */
    private static Map<String, String> AJLB_MAP = Maps.newHashMap();

    static {
        AJLB_MAP.put(YwlxEnum.MS.getCode().substring(0, 2), YwlxEnum.MS.getCode());
        AJLB_MAP.put(YwlxEnum.XS.getCode().substring(0, 2), YwlxEnum.XS.getCode());
        AJLB_MAP.put(YwlxEnum.XZ.getCode().substring(0, 2), YwlxEnum.XZ.getCode());
        AJLB_MAP.put(YwlxEnum.GX.getCode().substring(0, 2), YwlxEnum.GX.getCode());
        AJLB_MAP.put(YwlxEnum.FSBQSCAJ.getCode().substring(0, 2), YwlxEnum.FSBQSCAJ.getCode());
        AJLB_MAP.put(YwlxEnum.GJPCYSFJZ.getCode().substring(0, 2), YwlxEnum.GJPCYSFJZ.getCode());
        AJLB_MAP.put(YwlxEnum.SFZC.getCode().substring(0, 2), YwlxEnum.SFZC.getCode());
    }

    private DwjkToolUtils() {}

    /**
     *
     * Description: 根据总数进行分页来调用T3C的接口
     *
     * @param count 总数
     * @return List<Integer> 每个元素都是1000 最后一个元素小于等于一千
     * @throws
     *
     *         @author zhangyunfan
     * @date 2020年2月28日
     */
    public static List<Integer> getPageInfoByCount(Integer count) {
        List<Integer> page = Lists.newArrayList();
        // 只有一页
        if (count < LIMIT_MAX) {
            page.add(count);
            return page;
        }
        // 页数即调用次数，如果能整除一千就是多少页，如果不能就+1
        int pageNo = count % LIMIT_MAX > 0 ? count / LIMIT_MAX + 1 : count / LIMIT_MAX;
        // 前面总页数-1页每页肯定都是1000，就是最后一页有两种情况——1000或者求余的数
        for (int i = 0; i < pageNo - 1; i++) {
            page.add(LIMIT_MAX);
        }
        // 最后一页要么是1000要么就是求余
        page.add(count % LIMIT_MAX > 0 ? count % LIMIT_MAX : LIMIT_MAX);
        return page;
    }

    /**
     *
     * Description: 时间转字符串
     *
     * @param date 日期
     * @param isHour 是否包含小时
     * @return String
     * @throws
     *
     *         @author zhangyunfan
     * @date 2020年3月2日
     */
    public static String date2String(Date date, boolean isHour) {
        if (date == null) {
            return StringUtils.EMPTY;
        }
        Instant instant = date.toInstant();
        LocalDateTime localDateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        return isHour ? DATETIMEFORMATTER_HOUR.format(localDateTime) : DATETIMEFORMATTER.format(localDateTime);
    }

    /**
     * 
     * Description: String转Date
     * 
     * @param date
     * @param isHour
     * @return Date
     * @throws
     * 
     *         @author zhangyunfan
     * @date 2020年5月12日
     */
    public static Date String2Date(String date, boolean isHour) {
        if (StringUtils.isBlank(date)) {
            return null;
        }
        return Date.from(LocalDateTime.parse(date, isHour ? DATETIMEFORMATTER_HOUR : DATETIMEFORMATTER)
            .atZone(ZoneId.systemDefault()).toInstant());
    }

    /**
     *
     * Description: 根据业务类型获取案件类别
     *
     * @param ywlx 业务类型
     * @return String
     * @throws
     *
     *         @author zhangyunfan
     * @date 2020年3月3日
     */
    public static String getAjlbByYwlx(String ywlx) {
        if (StringUtils.isBlank(ywlx) || ywlx.length() < 2) {
            return StringUtils.EMPTY;
        }
        return AJLB_MAP.get(ywlx.substring(0, 2));
    }

    /**
     *
     * Description: base64解密成字符串
     *
     * @param baseStr base64 解密的字符串
     * @return String
     * @throws
     *
     *         @author zhangyunfan
     * @date 2020年3月10日
     */
    public static String base64Decode(String baseStr) {
        byte[] decoded = Base64Utils.decodeFromString(baseStr);
        try {
            return new String(decoded, DECODEING);
        } catch (UnsupportedEncodingException e) {
            log.error("{}解密失败", baseStr, e);
            return StringUtils.EMPTY;
        }
    }

    /**
     *
     * Description: base加密成字符串
     *
     * @param normolStr 普通字符串
     * @return String
     * @throws
     *
     *         @author zhangyunfan
     * @date 2020年3月10日
     */
    public static String base64Encode(String normolStr) {
        try {
            return Base64Utils.encodeToString(normolStr.getBytes(DECODEING));
        } catch (UnsupportedEncodingException e) {
            log.error("字符串{}base64加密失败", normolStr, e);
            return StringUtils.EMPTY;
        }
    }

    /**
     *
     * Description: 对用友的案件编号补齐
     *
     * @param bh 案件编号
     * @return String
     * @throws
     *
     *         @author zhangyunfan
     * @date 2020年3月31日
     */
    public static String complementBh(String bh) {
        if (StringUtils.isNotBlank(bh) && bh.length() == Const.YYAH_LENGTH) {
            return BH_COMPLEMENT + bh;
        }
        return bh;
    }

    /**
     *
     * Description: 间隔日期是否超过一年
     *
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return boolean
     * @throws
     *
     *         @author zhangyunfan
     * @date 2020年4月11日
     */
    public static boolean isMoreThanAYear(String startTime, String endTime) {
        return Period.between(LocalDate.parse(startTime), LocalDate.parse(endTime)).getYears() > 0;
    }

    /**
     *
     * Description: 把T3C的15位案件标识转换为11位的
     *
     * @param ajbs 案件标识
     * @return String
     * @throws
     *
     *         @author zhangyunfan
     * @date 2020年4月16日
     */
    public static String encodeAjbs(String ajbs) {
        if (StringUtils.isBlank(ajbs) || ajbs.length() != 15) {
            return ajbs;
        }
        // t3c的案件标识生成规则是0+年份+业务类型+序号
        String year = ajbs.substring(1, 5);
        String ywlx = ajbs.substring(5, 9);
        String xh = ajbs.substring(9, 15);
        // 科技法庭只能存11位的案件标识，我们去掉法院id和年份前两位和序号的第一个0来拼
        StringBuilder sb = new StringBuilder();
        return sb.append(year.substring(2, 4)).append(ywlx).append(xh.substring(1, 6)).toString();
    }

    public static void main(String[] args) {
        System.out.println(decodeAjbs("20030500055"));
    }

    /**
     *
     * Description: 把11位的案件标识转换成15位的
     *
     * @param ajbs 案件标识
     * @return String
     * @throws
     *
     *         @author zhangyunfan
     * @date 2020年4月16日
     */
    public static String decodeAjbs(String ajbs) {
        if (StringUtils.isBlank(ajbs) || ajbs.length() != 11) {
            return ajbs;
        }
        String year = ajbs.substring(0, 2);
        String ywlx = ajbs.substring(2, 6);
        String xh = ajbs.substring(6, 11);
        StringBuilder sb = new StringBuilder();
        return sb.append(Const.ZG_CORP_ID).append(Const.TwentyFirst_Century + year).append(ywlx).append("0" + xh)
            .toString();
    }

}
