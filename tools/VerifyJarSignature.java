import java.io.InputStream;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Verify JAR/AAB entry digests and coverage. Self-signed certificates are normal for Android. */
public final class VerifyJarSignature {
    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: java VerifyJarSignature.java app.aab");
        int files = 0, signed = 0;
        boolean signatureMaterial = false, debugCertificate = false;
        Set<String> certificates = new LinkedHashSet<>();
        List<String> unsigned = new ArrayList<>();
        try (JarFile jar = new JarFile(args[0], true)) {
            var entries = jar.entries();
            byte[] buffer = new byte[16384];
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                String upper = name.toUpperCase(java.util.Locale.ROOT);
                if (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                        || upper.endsWith(".DSA") || upper.endsWith(".EC"))) signatureMaterial = true;
                // Reading all bytes triggers the JDK verifier's manifest/signature digest checks.
                try (InputStream input = jar.getInputStream(entry)) {
                    while (input.read(buffer) != -1) { }
                }
                if (upper.startsWith("META-INF/")) continue;
                files++;
                CodeSigner[] signers = entry.getCodeSigners();
                if (signers == null || signers.length == 0) { unsigned.add(name); continue; }
                signed++;
                for (CodeSigner signer : signers) {
                    X509Certificate certificate = (X509Certificate) signer.getSignerCertPath().getCertificates().get(0);
                    certificates.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
                    if (certificate.getSubjectX500Principal().getName().contains("CN=Android Debug")) debugCertificate = true;
                }
            }
        } catch (SecurityException exception) {
            System.err.println("Invalid JAR/AAB signature: " + exception.getMessage());
            System.exit(2);
            return;
        }
        if (files == 0 || (signatureMaterial && (signed != files || certificates.isEmpty())) || (signed > 0 && signed != files)) {
            System.err.println("Invalid/incomplete JAR/AAB signature coverage: " + signed + "/" + files
                    + " signed entries; first unsigned: " + (unsigned.isEmpty() ? "none" : unsigned.get(0)));
            System.exit(2);
            return;
        }
        String certificateJson = certificates.stream().map(VerifyJarSignature::quote)
                .collect(java.util.stream.Collectors.joining(","));
        System.out.println("{\"status\":" + quote(signed == files ? "signed" : "unsigned")
                + ",\"files\":" + files + ",\"signed_files\":" + signed
                + ",\"debug_certificate\":" + debugCertificate
                + ",\"certificate_sha256\":[" + certificateJson + "]}");
    }
}
