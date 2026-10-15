package software.bernie.geckolib3.molang;

import org.jetbrains.annotations.Nullable;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import software.bernie.geckolib3.GeckoLib;
import software.bernie.geckolib3.molang.context.DebugSource;

/**
 * Debug plumbing for the MoLang runtime.
 * <p>
 * The 1.20.1 fork printed parse failures both to the log and to an in-game debug overlay. The engine keeps the same
 * two sinks but neither belongs to it: a host mod installs the overlay source, and when nothing is installed only the
 * log is written.
 */
public final class MolangDebug {

    private static final String PARSE_ERROR_KEY = "error.geckolib.parse_molang_exp";

    @Nullable
    private static volatile DebugSource globalSource;

    private MolangDebug() {}

    /** Installs (or clears) the debug sink, e.g. an on-screen overlay. */
    public static void setGlobalSource(@Nullable DebugSource source) {
        globalSource = source;
    }

    public static boolean isEnabled() {
        return globalSource != null;
    }

    /** Reports an expression the parser rejected, keeping the original error/expression pair together. */
    public static void logParseFailure(String molangExpression, Throwable error) {
        String expression = molangExpression == null ? "" : molangExpression.replace("\r\n", "\n")
            .replace("\r", "\n");
        DebugSource source = globalSource;
        if (source != null) {
            GeckoLib.LOG.error("Failed to parse molang expression: {}\n{}", error.getMessage(), molangExpression);
            IChatComponent message = new ChatComponentTranslation(PARSE_ERROR_KEY).appendText(
                String.valueOf(error.getMessage()))
                .appendText("\n----------------------\n")
                .appendText(expression)
                .appendText("\n----------------------");
            source.print(message);
        } else {
            GeckoLib.LOG.debug("Failed to parse molang expression: {}\n{}", error.getMessage(), molangExpression);
        }
    }

    /** A plain debug line, used by the {@code debug_output} query override path. */
    public static void print(String message) {
        DebugSource source = globalSource;
        if (source != null) {
            source.print("%s", message);
        } else {
            GeckoLib.LOG.info(message);
        }
    }

    /** Convenience for hosts that only have a string sink. */
    public static IChatComponent text(String message) {
        return new ChatComponentText(message);
    }
}
