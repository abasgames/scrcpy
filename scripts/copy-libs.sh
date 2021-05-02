#!/bin/bash -eu

executable="${1}"

LINUX_BLACKLIST="linux-vdso\.so|ld-linux-.+\.so|libpthread\.so|libc\.so|libm\.so|libdl\.so|libmvec\.so|libresolv\.so|libmvec\.so|librt\.so"

add_to_set()
{
    printf '%s\n' ${1} ${2} | sort | uniq
}

remove_from_set()
{
    printf '%s\n' ${1} | sort | uniq | grep -v ${2} || true
}

is_in_set()
{
    printf '%s\n' ${1} | grep ${2} > /dev/null
}

win32_find_library()
{
    local file

    file="${1}"
    if [ -e "${file}" ]; then
        echo "${file}"
        return 0
    fi

    file="$(dirname "${2}")/${1}"
    if [ -e "${file}" ]; then
        echo "${file}"
        return 0
    fi

    file="/usr/x86_64-w64-mingw32/bin/${1}"
    if [ -e "${file}" ]; then
        echo "${file}"
        return 0
    fi
}

win32_get_imports()
{
    local dependencies
    local todo
    dependencies="$@"
    todo="$@"
    while [ -n "${todo}" ]; do
        for item in ${todo}; do
            for import in $(x86_64-w64-mingw32-objdump --private-headers "${item}" | grep 'DLL Name' | cut -c12-); do
                local import_path
                import_path="$(win32_find_library "${import}" "${1}")"
                if [ -n "${import_path}" ]; then
                    if ! is_in_set "${dependencies}" "${import_path}"; then
                        dependencies="$(add_to_set "${dependencies}" "${import_path}")"
                        todo="$(add_to_set "${todo}" "${import_path}")"
                    fi
                fi
            done
            todo="$(remove_from_set "${todo}" "${item}")"
        done
    done
    echo "${dependencies}"
}

case "$(file --brief --mime-type "${executable}")" in
application/x-dosexec )
    cp -v $(win32_get_imports "${1}") "${2}"
    ;;

application/x-executable | application/x-pie-executable )
    cp -v "${1}" $(ldd "${1}" | grep -vP "${LINUX_BLACKLIST}" | perl -pe 's/.+=> (.+)\(.+/$1/') "${2}"
    ;;

*)
    echo "File is not an executable" >&2
    exit 1
    ;;
esac
