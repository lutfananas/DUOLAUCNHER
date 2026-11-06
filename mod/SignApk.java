import com.android.apksig.ApkSigner;
import java.io.*; import java.security.*; import java.security.cert.X509Certificate; import java.util.*;
public class SignApk {
  public static void main(String[] a) throws Exception {
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (InputStream in = new FileInputStream(a[0])) { ks.load(in, a[1].toCharArray()); }
    String alias = ks.aliases().nextElement();
    PrivateKey key = (PrivateKey) ks.getKey(alias, a[1].toCharArray());
    X509Certificate cert = (X509Certificate) ks.getCertificate(alias);
    ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("duo", key, List.of(cert)).build();
    new ApkSigner.Builder(List.of(sc)).setInputApk(new File(a[2])).setOutputApk(new File(a[3]))
      .setMinSdkVersion(31).setAlignmentPreserved(true).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true).build().sign();
    System.out.println("signed " + a[3]);
  }
}
