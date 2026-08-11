using TestDPLS.Protocol;

namespace TestDPLS.Core.Tests;

public class DplsProtocolTests
{
    [Fact]
    public void Crc16_MatchesCcittFalseCheckVector()
    {
        var data = "123456789"u8.ToArray();
        Assert.Equal(0x29B1, DplsProtocol.Crc16(data));
    }

    [Fact]
    public void EncodeThenDecode_RoundTripsFrame()
    {
        var payload = new byte[] { 0x11, 0x22, 0x33, 0x44, 0x55 };
        var frame = new DplsProtocol.Frame(DplsProtocol.MessageType.ModeSet, 0x1234, payload);

        var encoded = DplsProtocol.Encode(frame);
        Assert.Equal(DplsProtocol.Overhead + payload.Length, encoded.Length);

        var decoded = DplsProtocol.Decode(encoded);
        var success = Assert.IsType<DplsProtocol.DecodeResult.Success>(decoded);
        Assert.Equal(DplsProtocol.MessageType.ModeSet, success.Frame.Type);
        Assert.Equal((ushort)0x1234, success.Frame.Sequence);
        Assert.Equal(payload, success.Frame.Payload);
    }

    [Fact]
    public void Encode_Layout_IsVersionTypeFlagsSeqLenPayloadCrc()
    {
        var frame = new DplsProtocol.Frame(DplsProtocol.MessageType.Hello, 1, []);
        var encoded = DplsProtocol.Encode(frame);
        Assert.Equal(DplsProtocol.Overhead, encoded.Length);
        Assert.Equal(DplsProtocol.Version, encoded[0]);
        Assert.Equal((byte)DplsProtocol.MessageType.Hello, encoded[1]);
        Assert.Equal(0, encoded[2]);
        Assert.Equal(1, encoded[3]);
        Assert.Equal(0, encoded[4]);
        Assert.Equal(0, encoded[5]);
        Assert.Equal(0, encoded[6]);
    }

    [Fact]
    public void Decode_RejectsCorruptCrc()
    {
        var encoded = DplsProtocol.Encode(
            new DplsProtocol.Frame(DplsProtocol.MessageType.StateGet, 7, [1, 2, 3]));
        encoded[^1]++;
        Assert.IsType<DplsProtocol.DecodeResult.Failure>(DplsProtocol.Decode(encoded));
    }

    [Fact]
    public void Decode_RejectsWrongLength()
    {
        var encoded = DplsProtocol.Encode(
            new DplsProtocol.Frame(DplsProtocol.MessageType.StateGet, 7, [1, 2, 3]));
        Assert.IsType<DplsProtocol.DecodeResult.Failure>(DplsProtocol.Decode(encoded.AsSpan(0, encoded.Length - 1)));
    }

    [Fact]
    public void Decode_RejectsUnknownVersion()
    {
        var encoded = DplsProtocol.Encode(
            new DplsProtocol.Frame(DplsProtocol.MessageType.KeepAlive, 0, []));
        encoded[0] = 0x7f;
        Assert.IsType<DplsProtocol.DecodeResult.Failure>(DplsProtocol.Decode(encoded));
    }
}
