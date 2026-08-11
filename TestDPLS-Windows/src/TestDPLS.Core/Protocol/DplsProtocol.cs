using System.Buffers.Binary;

namespace TestDPLS.Protocol;

/// <summary>
/// Wire framing shared with firmware, Android and iOS clients.
/// CRC-16/CCITT-FALSE (init 0xFFFF, poly 0x1021, no reflection).
/// </summary>
public static class DplsProtocol
{
    public const byte Version = 0x01;
    public const int HeaderSize = 7;
    public const int TrailerSize = 2;
    public const int Overhead = HeaderSize + TrailerSize;

    public enum MessageType : byte
    {
        Hello = 0x01,
        AuthChallenge = 0x02,
        AuthProof = 0x03,
        AuthResult = 0x04,
        Setup = 0x05,
        DeviceInfoGet = 0x06,
        DeviceInfoReport = 0x07,
        NameSet = 0x08,
        PasswordSet = 0x09,
        SettingsResult = 0x0a,
        StateGet = 0x10,
        StateReport = 0x11,
        ModeSet = 0x12,
        CommandResult = 0x13,
        IdentifyStart = 0x14,
        IdentifyStop = 0x15,
        LogStart = 0x20,
        LogInfo = 0x21,
        LogChunk = 0x22,
        LogAck = 0x23,
        LogFinish = 0x24,
        LogResult = 0x25,
        KeepAlive = 0x30,
        Error = 0x7f,
    }

    public readonly record struct Frame(MessageType Type, ushort Sequence, byte[] Payload, byte Flags = 0);

    public abstract record DecodeResult
    {
        public sealed record Success(Frame Frame) : DecodeResult;
        public sealed record Failure(string Reason) : DecodeResult;
    }

    public static byte[] Encode(Frame frame)
    {
        if (frame.Payload.Length > 0xffff)
            throw new ArgumentOutOfRangeException(nameof(frame), "Payload too large");

        var bytes = new byte[Overhead + frame.Payload.Length];
        bytes[0] = Version;
        bytes[1] = (byte)frame.Type;
        bytes[2] = frame.Flags;
        BinaryPrimitives.WriteUInt16LittleEndian(bytes.AsSpan(3), frame.Sequence);
        BinaryPrimitives.WriteUInt16LittleEndian(bytes.AsSpan(5), (ushort)frame.Payload.Length);
        frame.Payload.CopyTo(bytes, HeaderSize);
        var crc = Crc16(bytes.AsSpan(0, HeaderSize + frame.Payload.Length));
        BinaryPrimitives.WriteUInt16LittleEndian(bytes.AsSpan(HeaderSize + frame.Payload.Length), crc);
        return bytes;
    }

    public static DecodeResult Decode(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length < Overhead)
            return new DecodeResult.Failure("Короткий кадр");
        if (bytes[0] != Version)
            return new DecodeResult.Failure("Версия протокола не поддерживается");
        if (!Enum.IsDefined(typeof(MessageType), bytes[1]))
            return new DecodeResult.Failure("Неизвестный тип сообщения");

        var type = (MessageType)bytes[1];
        var flags = bytes[2];
        var sequence = BinaryPrimitives.ReadUInt16LittleEndian(bytes.Slice(3));
        var payloadLength = BinaryPrimitives.ReadUInt16LittleEndian(bytes.Slice(5));
        if (bytes.Length != Overhead + payloadLength)
            return new DecodeResult.Failure("Неверная длина кадра");

        var expected = BinaryPrimitives.ReadUInt16LittleEndian(bytes.Slice(bytes.Length - 2));
        var actual = Crc16(bytes.Slice(0, bytes.Length - 2));
        if (expected != actual)
            return new DecodeResult.Failure("Ошибка CRC16");

        var payload = bytes.Slice(HeaderSize, payloadLength).ToArray();
        return new DecodeResult.Success(new Frame(type, sequence, payload, flags));
    }

    /// <summary>CRC-16/CCITT-FALSE — same as firmware.</summary>
    public static ushort Crc16(ReadOnlySpan<byte> bytes)
    {
        ushort crc = 0xffff;
        foreach (var b in bytes)
        {
            crc ^= (ushort)(b << 8);
            for (var i = 0; i < 8; i++)
                crc = (crc & 0x8000) != 0 ? (ushort)((crc << 1) ^ 0x1021) : (ushort)(crc << 1);
        }
        return crc;
    }
}

public static class LittleEndian
{
    public static byte U8(ReadOnlySpan<byte> data, ref int offset)
    {
        var v = data[offset];
        offset += 1;
        return v;
    }

    public static ushort U16(ReadOnlySpan<byte> data, ref int offset)
    {
        var v = BinaryPrimitives.ReadUInt16LittleEndian(data.Slice(offset));
        offset += 2;
        return v;
    }

    public static uint U32(ReadOnlySpan<byte> data, ref int offset)
    {
        var v = BinaryPrimitives.ReadUInt32LittleEndian(data.Slice(offset));
        offset += 4;
        return v;
    }

    public static void AppendU8(List<byte> data, byte value) => data.Add(value);

    public static void AppendU16(List<byte> data, ushort value)
    {
        Span<byte> tmp = stackalloc byte[2];
        BinaryPrimitives.WriteUInt16LittleEndian(tmp, value);
        data.AddRange(tmp.ToArray());
    }

    public static void AppendU32(List<byte> data, uint value)
    {
        Span<byte> tmp = stackalloc byte[4];
        BinaryPrimitives.WriteUInt32LittleEndian(tmp, value);
        data.AddRange(tmp.ToArray());
    }

    public static byte[] Concat(params byte[][] parts)
    {
        var len = 0;
        foreach (var p in parts) len += p.Length;
        var result = new byte[len];
        var o = 0;
        foreach (var p in parts)
        {
            p.CopyTo(result, o);
            o += p.Length;
        }
        return result;
    }
}
