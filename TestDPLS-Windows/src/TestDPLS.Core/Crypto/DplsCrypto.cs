using System.Security.Cryptography;
using System.Text;

namespace TestDPLS.Crypto;

public static class DplsCrypto
{
    public const int Pbkdf2Iterations = 10_000;

    public static byte[] DeriveVerifier(string password, ReadOnlySpan<byte> salt)
    {
        var passwordBytes = Encoding.UTF8.GetBytes(password);
        return Rfc2898DeriveBytes.Pbkdf2(
            passwordBytes,
            salt.ToArray(),
            Pbkdf2Iterations,
            HashAlgorithmName.SHA256,
            32);
    }

    public static byte[] HmacSha256(ReadOnlySpan<byte> key, ReadOnlySpan<byte> message)
    {
        using var hmac = new HMACSHA256(key.ToArray());
        return hmac.ComputeHash(message.ToArray());
    }

    public static byte[] RandomBytes(int count)
    {
        var data = new byte[count];
        RandomNumberGenerator.Fill(data);
        return data;
    }
}
