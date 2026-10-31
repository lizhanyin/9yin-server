# -*- coding: utf-8 -*-
"""Dump the live fxgame.exe module image (unpacked in memory) to a file for IDA.

Usage: python dump_fxgame.py [pid]
Finds fxgame.exe module base+size via Toolhelp snapshot, reads the whole image
with ReadProcessMemory, writes <base>.exe dump next to this script.
"""
import ctypes
import ctypes.wintypes as wt
import os
import struct
import sys

TH32CS_SNAPMODULE = 0x00000008
TH32CS_SNAPMODULE32 = 0x00000010
PROCESS_VM_READ = 0x0010
PROCESS_QUERY_INFORMATION = 0x0400

k32 = ctypes.WinDLL("kernel32", use_last_error=True)


class MODULEENTRY32W(ctypes.Structure):
    _fields_ = [
        ("dwSize", wt.DWORD),
        ("th32ModuleID", wt.DWORD),
        ("th32ProcessID", wt.DWORD),
        ("GlblcntUsage", wt.DWORD),
        ("ProccntUsage", wt.DWORD),
        ("modBaseAddr", ctypes.POINTER(wt.BYTE)),
        ("modBaseSize", wt.DWORD),
        ("hModule", wt.HMODULE),
        ("szModule", ctypes.c_wchar * 256),
        ("szExePath", ctypes.c_wchar * 260),
    ]


def find_module(pid, name):
    snap = k32.CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32, pid)
    if snap == wt.HANDLE(-1).value or not snap:
        raise OSError("snapshot failed %d" % ctypes.get_last_error())
    try:
        me = MODULEENTRY32W()
        me.dwSize = ctypes.sizeof(MODULEENTRY32W)
        ok = k32.Module32FirstW(snap, ctypes.byref(me))
        while ok:
            if me.szModule.lower() == name.lower():
                return ctypes.cast(me.modBaseAddr, ctypes.c_void_p).value, me.modBaseSize
            ok = k32.Module32NextW(snap, ctypes.byref(me))
        return None
    finally:
        k32.CloseHandle(snap)


def main():
    pid = int(sys.argv[1]) if len(sys.argv) > 1 else None
    if pid is None:
        # find any fxgame.exe process
        class PROCESSENTRY32W(ctypes.Structure):
            _fields_ = [
                ("dwSize", wt.DWORD), ("cntUsage", wt.DWORD), ("th32ProcessID", wt.DWORD),
                ("th32DefaultHeapID", ctypes.POINTER(wt.ULONG)), ("th32ModuleID", wt.DWORD),
                ("cntThreads", wt.DWORD), ("th32ParentProcessID", wt.DWORD),
                ("pcPriClassBase", wt.LONG), ("dwFlags", wt.DWORD), ("szExeFile", ctypes.c_wchar * 260),
            ]
        snap = k32.CreateToolhelp32Snapshot(2, 0)  # TH32CS_SNAPPROCESS
        pe = PROCESSENTRY32W()
        pe.dwSize = ctypes.sizeof(PROCESSENTRY32W)
        ok = k32.Process32FirstW(snap, ctypes.byref(pe))
        while ok:
            if pe.szExeFile.lower() == "fxgame.exe":
                pid = pe.th32ProcessID
                break
            ok = k32.Process32NextW(snap, ctypes.byref(pe))
        k32.CloseHandle(snap)
    if pid is None:
        print("fxgame.exe not running")
        return 1

    h = k32.OpenProcess(PROCESS_VM_READ | PROCESS_QUERY_INFORMATION, False, pid)
    if not h:
        print("OpenProcess failed err=%d" % ctypes.get_last_error())
        return 1
    try:
        mod = find_module(pid, "fxgame.exe")
        if not mod:
            print("module not found")
            return 1
        base, size = mod
        print("pid=%d base=0x%x size=0x%x (%.1f MB)" % (pid, base, size, size / 1048576.0))
        buf = ctypes.create_string_buffer(size)
        nread = ctypes.c_size_t(0)
        # read in chunks so partial failures are visible
        CH = 1 << 20
        got = 0
        while got < size:
            chunk = min(CH, size - got)
            ok = k32.ReadProcessMemory(h, ctypes.c_void_p(base + got), ctypes.byref(buf, got), chunk, ctypes.byref(nread))
            if not ok or nread.value == 0:
                print("  read failed at +0x%x err=%d, zero-filling rest" % (got, ctypes.get_last_error()))
                break
            got += nread.value
        data = buf.raw[:size]
        # PE sanity
        if data[:2] != b"MZ":
            print("no MZ at base!")
        e_lfanew = struct.unpack_from("<I", data, 0x3C)[0]
        machine, nsec = struct.unpack_from("<HH", data, e_lfanew + 4)[0:2]
        print("PE machine=0x%x sections=%d" % (machine, nsec))
        out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fxgame_mem_dump.exe")
        with open(out, "wb") as f:
            f.write(data)
        print("written", out, len(data))
        return 0
    finally:
        k32.CloseHandle(h)


if __name__ == "__main__":
    sys.exit(main())
