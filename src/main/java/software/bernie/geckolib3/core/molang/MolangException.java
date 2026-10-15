package software.bernie.geckolib3.core.molang;

// import java.io.Serial;

public class MolangException extends Exception {
    // @Serial
    // private static final long serialVersionUID = 1470247726869768015L;

    public MolangException(String message) {
        super(message);
    }

    /**
     * With the cause attached. The parser's outer catch sites used to wrap without it, so a failure surfaced as
     * "Couldn't parse '&lt;the whole expression&gt;' expression!" and the reason the parser actually disliked -
     * an unknown operator, an unknown function, a bad argument list - was discarded before anyone could read it.
     */
    public MolangException(String message, Throwable cause) {
        super(message, cause);
    }
}
