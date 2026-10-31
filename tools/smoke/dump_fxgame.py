# -*- coding: utf-8 -*-
"""Dump the live fxgame.exe module image (unpacked in memory) for IDA.

Elevated run recommended (game process strips VM_READ at medium IL):
  powershell -ExecutionPolicy Bypass -File run_dump_elevated.ps1

Gets the image base from the PEB via NtQueryInformationProcess (toolhelp
module snapshot is blocked by the game's anti-tamper), then reads the whole
image with ReadProcessMemory and writes fxgame_mem_dump.exe.
"""
import ctypes
import ctypes.wintypes as wt
import os
import struct
import sys

PROCESS_VM_READ = 0x0010
PROCESS_QUERY_INFORMATION = 0x0400

k32 = ctypes.WinDLL("kernel32", use_last_error=True)
ntdll = ctypes.WinDLL("ntdll", use_last_error=True)


class PROCESS_BASIC_INFORMATION(ctypes.Structure):
    _fields_ = [
        ("ExitStatus", ctypes.c_void_p),
        ("PebBaseAddress", ctypes.c_void_p),
        ("AffinityMask", ctypes.c_void_p),
        ("BasePriority", ctypes.c_void_p),
        ("UniqueProcessId", ctypes.c_void_p),
        ("InheritedFromUniqueProcessId", ctypes.c_void_p),
    ]


def find_pid():
    class PROCESSENTRY32W(ctypes.Structure):
        _fields_ = [
            ("dwSize", wt.DWORD), ("cntUsage", wt.DWORD), ("th32ProcessID", wt.DWORD),
            ("th32DefaultHeapID", ctypes.POINTER(wt.ULONG)), ("th32ModuleID", wt.DWORD),
            ("cntThreads", wt.DWORD), ("th32ParentProcessID", wt.DWORD),
            ("pcPriClassBase", wt.LONG), ("dwFlags", wt.DWORD), ("szExeFile", ctypes.c_wchar * 260),
        ]
    snap = k32.CreateToolhelp32Snapshot(2, 0)
    pe = PROCESSENTRY32W()
    pe.dwSize = ctypes.sizeof(PROCESSENTRY32W)
    pid = None
    ok = k32.Process32FirstW(snap, ctypes.byref(pe))
    while ok:
        if pe.szExeFile.lower() == "fxgame.exe":
            pid = pe.th32ProcessID
            break
        ok = k32.Process32NextW(snap, ctypes.byref(pe))
    k32.CloseHandle(snap)
    return pid


def read_mem(h, addr, size):
    buf = ctypes.create_string_buffer(size)
    got = ctypes.c_size_t(0)
    ok = k32.ReadProcessMemory(h, ctypes.c_void_p(addr), buf, size, ctypes.byref(got))
    if not ok:
        return None, ctypes.get_last_error()
    return buf.raw[:got.value], 0


def main():
    pid = int(sys.argv[1]) if len(sys.argv) > 1 else find_pid()
    if pid is None:
        print("fxgame.exe not running")
        return 1
    h = k32.OpenProcess(PROCESS_VM_READ | PROCESS_QUERY_INFORMATION, False, pid)
    if not h:
        print("OpenProcess(%d) failed err=%d" % (pid, ctypes.get_last_error()))
        return 1
    try:
        pbi = PROCESS_BASIC_INFORMATION()
        rl = ctypes.c_ulong(0)
        st = ntdll.NtQueryInformationProcess(h, 0, ctypes.byref(pbi), ctypes.sizeof(pbi), ctypes.byref(rl))
        if st != 0:
            print("NtQueryInformationProcess failed 0x%x" % (st & 0xFFFFFFFF))
            return 1
        print("pid=%d peb=0x%x" % (pid, pbi.PebBaseAddress or 0))
        peb, err = read_mem(h, pbi.PebBaseAddress, 0x100)
        if peb is None:
            print("read peb failed err=%d" % err)
            return 1
        base = struct.unpack_from("<Q", peb, 0x10)[0]
        print("image base=0x%x" % base)
        hdr, err = read_mem(h, base, 0x1000)
        if hdr is None or hdr[:2] != b"MZ":
            print("read image header failed err=%s" % err)
            return 1
        e_lfanew = struct.unpack_from("<I", hdr, 0x3C)[0]
        size_of_image = struct.unpack_from("<I", hdr, e_lfanew + 0x50)[0]
        nsec = struct.unpack_from("<H", hdr, e_lfanew + 6)[0]
        print("SizeOfImage=0x%x sections=%d" % (size_of_image, nsec))

        CH = 1 << 22
        data = bytearray(size_of_image)
        got_total = 0
        off = 0
        while off < size_of_image:
            chunk = min(CH, size_of_image - off)
            piece, err = read_mem(h, base + off, chunk)
            if piece is None:
                print("  read gap at +0x%x err=%d (zero-filled)" % (off, err))
                # fall back to page-by-page to salvage as much as possible
                for p in range(off, min(off + chunk, size_of_image), 0x1000):
                    pp, _e = read_mem(h, base + p, 0x1000)
                    if pp:
                        data[p:p + len(pp)] = pp
                        got_total += len(pp)
                off += chunk
                continue
            data[off:off + len(piece)] = piece
            got_total += len(piece)
            off += chunk
        print("read %d / %d bytes" % (got_total, size_of_image))
        out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fxgame_mem_dump.exe")
        with open(out, "wb") as f:
            f.write(bytes(data))
        print("written", out)
        return 0
    finally:
        k32.CloseHandle(h)


if __name__ == "__main__":
    sys.exit(main())
