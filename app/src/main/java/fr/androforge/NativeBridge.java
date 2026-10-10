package fr.androforge;

/** The patch engine owns parsing, edits, history and the official circuit catalogue. */
public final class NativeBridge {
    static {
        System.loadLibrary("androforge");
    }

    private NativeBridge() {}

    public static native String load(String source);
    public static native String replaceSource(String source);
    public static native String source();
    public static native String state();
    public static native String catalogue();
    public static native String prepareDeployment();
    public static native byte[] activationMessage(String deployment);
    public static native String addCircuit(String name);
    public static native String addCircuitWithSelection(String name, int selection);
    public static native String setParameter(int block, String name, String value);
    public static native String analyzeExpression(String value, boolean output);
    public static native String composeExpression(String base, String factor, String offset, boolean output);
    public static native String setParameterExpression(int block, String name, String base, String factor, String offset);
    public static native String connectParameters(int fromBlock, String fromParam, int fromColumn, String fromDraftValue,
                                                 int toBlock, String toParam, int toColumn, String toDraftValue, String cableName);
    public static native String connectParameterExpressions(int fromBlock, String fromParam, int fromColumn,
                                                           String fromBase, String fromFactor, String fromOffset,
                                                           int toBlock, String toParam, int toColumn,
                                                           String toBase, String toFactor, String toOffset, String cableName);
    public static native String cables();
    public static native String renameCable(String oldName, String newName);
    public static native String renameCableWithMerge(String oldName, String newName, boolean allowMerge);
    public static native String deleteCable(String name);
    public static native String setRegisterLabel(String register, String shorthand, String description);
    public static native String setBlockComment(int block, String text);
    public static native String removeCircuit(int block);
    public static native String undo();
    public static native String redo();
}
