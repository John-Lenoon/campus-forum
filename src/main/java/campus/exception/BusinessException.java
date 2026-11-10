package campus.exception;

public class BusinessException extends RuntimeException {
    private final int status;
    private final String code;

    public BusinessException(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static BusinessException fail(int status, String code) {
        return new BusinessException(status, code);
    }
}
