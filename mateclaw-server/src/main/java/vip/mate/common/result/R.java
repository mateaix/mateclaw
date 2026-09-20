package vip.mate.common.result;

import lombok.Data;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一响应结果封装
 *
 * @author MateClaw Team
 */
@Data
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态码 */
    private int code;

    /** 提示信息 */
    private String msg;

    /** 数据 */
    private T data;

    /** i18n holder — set once at startup by I18nAutoConfig, used by ok()/fail() */
    private static final AtomicReference<vip.mate.i18n.I18nService> I18N = new AtomicReference<>();

    public static void setI18n(vip.mate.i18n.I18nService service) { I18N.set(service); }

    /** Clear a closing context's service without clobbering a newer context. */
    public static void clearI18n(vip.mate.i18n.I18nService service) {
        I18N.compareAndSet(service, null);
    }

    private static String resolveMsg(ResultCode rc) {
        vip.mate.i18n.I18nService service = I18N.get();
        return service != null ? rc.getMsg(service) : rc.getMsg();
    }

    public static <T> R<T> ok() {
        return result(ResultCode.SUCCESS.getCode(), resolveMsg(ResultCode.SUCCESS), null);
    }

    public static <T> R<T> ok(T data) {
        return result(ResultCode.SUCCESS.getCode(), resolveMsg(ResultCode.SUCCESS), data);
    }

    public static <T> R<T> ok(String msg, T data) {
        return result(ResultCode.SUCCESS.getCode(), msg, data);
    }

    public static <T> R<T> fail() {
        return result(ResultCode.SYSTEM_ERROR.getCode(), resolveMsg(ResultCode.SYSTEM_ERROR), null);
    }

    public static <T> R<T> fail(String msg) {
        return result(ResultCode.SYSTEM_ERROR.getCode(), msg, null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return result(code, msg, null);
    }

    private static <T> R<T> result(int code, String msg, T data) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setData(data);
        return r;
    }
}
