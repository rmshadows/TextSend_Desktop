#!/usr/bin/env bash
# 3/3  Linux .deb（jpackage，自带 JRE）
# 用法：./pack/pack-deb.sh
# 产物：
#   dist/TextSend-<ver>-linux-<arch>-installer.deb         — 原版：Depends 按本机扫库名
#   dist/TextSend-<ver>-linux-<arch>-installer-compat.deb  — 宽松：libasound2 | libasound2t64（Debian 12/13、Ubuntu）
#
# compat 不二次 jpackage（JDK 17 + Actions 上易 dpkg-deb exit 2）：从原版解包改 Depends 再打。
# 重打包不用 fakeroot：Ubuntu 24.04 上 fakeroot + dpkg-deb（zstd）会把 tar 管道打断，
# 报 tar: stdout: write error / exit 2，amd64 上更容易出现。改用 --root-owner-group 和 gzip。
# 菜单项：jpackage 只把 .desktop 放在 /opt/.../lib/ 并靠 postinst 的 xdg-desktop-menu，
# Debian/Ubuntu 上经常装完没有开始菜单入口。解包后写入 /usr/share/applications/。
#
# 缩放等配置：程序目录能写就写旁边；/opt 不能写则退回用户主目录 .textsend.properties。
set -euo pipefail
# shellcheck source=common.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo ".deb 只能在 Linux 上打" >&2
  exit 1
fi

need jpackage
need dpkg-deb

# jpackage 只把 .desktop 放进 /opt/textsend/lib/，靠 xdg-desktop-menu 注册；
# 系统安装包应直接带 /usr/share/applications/，否则 GNOME/KDE 经常没有入口。
inject_menu_entry() {
  local root="$1"
  local apps="$root/usr/share/applications"
  local pixmaps="$root/usr/share/pixmaps"
  local menu_src="$PACK_DIR/jpackage-resources/textsend.desktop"
  local cli_src="$PACK_DIR/jpackage-resources/textsend-cli.desktop"
  mkdir -p "$apps" "$pixmaps"

  if [[ ! -f "$cli_src" ]]; then
    echo "缺少 $cli_src" >&2
    exit 1
  fi
  cp -f "$cli_src" "$apps/textsend-cli.desktop"
  chmod 644 "$apps/textsend-cli.desktop"

  if [[ -f "$menu_src" ]]; then
    cp -f "$menu_src" "$apps/textsend.desktop"
  else
    cat > "$apps/textsend.desktop" << 'EOF'
[Desktop Entry]
Type=Application
Name=TextSend
Comment=局域网文字互传
Exec=/opt/textsend/bin/TextSend
Icon=/usr/share/pixmaps/textsend.png
Terminal=false
Categories=Utility;
StartupWMClass=TextSend
StartupNotify=true
EOF
  fi
  chmod 644 "$apps/textsend.desktop"

  if [[ -f "$ICON_PNG" ]]; then
    cp -f "$ICON_PNG" "$pixmaps/textsend.png"
  else
    local jp_icon=""
    jp_icon="$(find "$root/opt/textsend/lib" -maxdepth 1 -name '*.png' 2>/dev/null | head -1 || true)"
    if [[ -n "$jp_icon" ]]; then
      cp -f "$jp_icon" "$pixmaps/textsend.png"
    else
      echo "警告：没有图标可写入 /usr/share/pixmaps/textsend.png" >&2
    fi
  fi
  if [[ -f "$pixmaps/textsend.png" ]]; then
    chmod 644 "$pixmaps/textsend.png"
  fi

  # 避免 postinst 再跑 xdg-desktop-menu：失败会让 dpkg 报错，成功则出现两个入口
  local script
  for script in "$root/DEBIAN/postinst" "$root/DEBIAN/postinstall"; do
    if [[ -f "$script" ]]; then
      sed -i -E '/xdg-desktop-menu[[:space:]]+install/s/^/# /' "$script"
      sed -i -E '/xdg-desktop-icon[[:space:]]+install/s/^/# /' "$script"
    fi
  done
  for script in "$root/DEBIAN/prerm" "$root/DEBIAN/postrm"; do
    if [[ -f "$script" ]]; then
      sed -i -E '/xdg-desktop-menu[[:space:]]+uninstall/s/$/ || true/' "$script"
      sed -i -E '/xdg-desktop-icon[[:space:]]+uninstall/s/$/ || true/' "$script"
    fi
  done
}

refresh_deb_metadata() {
  local root="$1"
  local control="$root/DEBIAN/control"
  ( cd "$root" && find . -type f ! -path './DEBIAN/*' | sed 's|^\./||' | sort | xargs -r md5sum > DEBIAN/md5sums )
  local sz=0
  if [[ -d "$root/opt" ]]; then
    sz=$((sz + $(du -sk "$root/opt" | cut -f1)))
  fi
  if [[ -d "$root/usr" ]]; then
    sz=$((sz + $(du -sk "$root/usr" | cut -f1)))
  fi
  if grep -q '^Installed-Size:' "$control"; then
    sed -i "s/^Installed-Size:.*/Installed-Size: ${sz}/" "$control"
  else
    printf 'Installed-Size: %s\n' "$sz" >> "$control"
  fi
  if [[ -n "$(tail -c1 "$control" || true)" ]]; then
    printf '\n' >> "$control"
  fi
}

assert_deb_has_menu() {
  local deb="$1"
  local listing
  listing="$(dpkg-deb -c "$deb")" || {
    echo "$deb 无法列出内容" >&2
    exit 1
  }
  if ! grep -q 'usr/share/applications/textsend.desktop' <<<"$listing"; then
    echo "$deb 缺少 /usr/share/applications/textsend.desktop" >&2
    exit 1
  fi
  if ! grep -q 'usr/share/applications/textsend-cli.desktop' <<<"$listing"; then
    echo "$deb 缺少 /usr/share/applications/textsend-cli.desktop" >&2
    exit 1
  fi
  if ! grep -q 'opt/textsend/bin/TextSend$' <<<"$listing"; then
    echo "$deb 缺少 /opt/textsend/bin/TextSend" >&2
    exit 1
  fi
  if ! grep -q 'opt/textsend/bin/TextSend-console$' <<<"$listing"; then
    echo "$deb 缺少 /opt/textsend/bin/TextSend-console" >&2
    exit 1
  fi
}

# 写到临时文件再换上，避免失败时把原包截断。gzip 避开 zstd 管道在 Actions 上被打断。
rebuild_deb() {
  local root="$1"
  local out="$2"
  local tmp
  tmp="$(mktemp "${out}.XXXXXX")"
  if ! dpkg-deb --root-owner-group -Zgzip -b "$root" "$tmp"; then
    rm -f "$tmp"
    return 1
  fi
  mv -f "$tmp" "$out"
}

# dpkg 解析 control 需要 UTF-8（描述里有中文）
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"

if [[ "${SKIP_JAR:-}" != 1 ]]; then
  "$PACK_DIR/pack-jar.sh"
elif [[ ! -f "$FAT_JAR" ]]; then
  "$PACK_DIR/pack-jar.sh"
fi

rm -rf "$JPACKAGE_INPUT"
mkdir -p "$JPACKAGE_INPUT"
cp -f "$FAT_JAR" "$JPACKAGE_INPUT/TextSend.jar"

echo "==> jpackage deb"
rm -f "$DIST"/textsend_*.deb "$DIST"/TextSend-*-installer.deb "$DIST"/TextSend-*-installer-compat.deb

ICON_JP=()
if [[ -f "$ICON_PNG" ]]; then
  ICON_JP=(--icon "$ICON_PNG")
else
  echo "警告：找不到 $ICON_PNG，.deb 将用默认 Java 图标" >&2
fi

JPACKAGE_RESOURCES="$PACK_DIR/jpackage-resources"
DESKTOP_IN="$JPACKAGE_RESOURCES/TextSend.desktop"

if [[ ! -f "$DESKTOP_IN" ]]; then
  echo "缺少 $DESKTOP_IN" >&2
  exit 1
fi
MENU_DESKTOP="$JPACKAGE_RESOURCES/textsend.desktop"
if [[ ! -f "$MENU_DESKTOP" ]]; then
  echo "缺少 $MENU_DESKTOP" >&2
  exit 1
fi
if [[ ! -f "$JP_CONSOLE_PROPS" ]]; then
  echo "缺少 $JP_CONSOLE_PROPS" >&2
  exit 1
fi

# 宽松 Depends（与旧 jpackage-resources/control 一致）
COMPAT_DEPENDS='Depends: libc6, libasound2 | libasound2t64, libx11-6, libxext6, libxi6, libxrender1, libxtst6, xdg-utils'

RES_ORIG="$(mktemp -d)"
cp -f "$DESKTOP_IN" "$RES_ORIG/"
echo "==> deb 原版（扫库名）"
jpackage \
  --type deb \
  --name TextSend \
  --linux-package-name textsend \
  --app-version "$APP_VERSION" \
  --vendor "TextSend" \
  --linux-deb-maintainer "noreply@users.noreply.github.com" \
  --description "局域网文字互传" \
  --dest "$DIST" \
  --input "$JPACKAGE_INPUT" \
  --main-jar TextSend.jar \
  --main-class "$MAIN_CLASS" \
  --add-modules "$JP_MODULES" \
  --add-launcher "TextSend-console=$JP_CONSOLE_PROPS" \
  --linux-shortcut \
  --linux-menu-group Utility \
  --resource-dir "$RES_ORIG" \
  "${ICON_JP[@]}" \
  --java-options "-Dfile.encoding=UTF-8" \
  --java-options '--add-opens=java.desktop/sun.awt=ALL-UNNAMED' \
  --java-options '--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED' \
  --java-options '-Dtextsend.home=$ROOTDIR'
rm -rf "$RES_ORIG"

ORIG_DEB="$(ls -1 "$DIST"/textsend_*.deb | head -1)"
if [[ -z "$ORIG_DEB" || ! -f "$ORIG_DEB" ]]; then
  echo "jpackage 未产出 .deb" >&2
  exit 1
fi
BASE="$(basename "$ORIG_DEB" .deb)"
echo "原版：$ORIG_DEB"

echo "==> 写入开始菜单项 /usr/share/applications/textsend.desktop"
TMP_ROOT="$(mktemp -d)"
dpkg-deb -R "$ORIG_DEB" "$TMP_ROOT"
CONTROL="$TMP_ROOT/DEBIAN/control"
if [[ ! -f "$CONTROL" ]]; then
  echo "解包后缺少 DEBIAN/control" >&2
  rm -rf "$TMP_ROOT"
  exit 1
fi
inject_menu_entry "$TMP_ROOT"
refresh_deb_metadata "$TMP_ROOT"
if ! rebuild_deb "$TMP_ROOT" "$ORIG_DEB"; then
  echo "写入菜单项后重打原版 .deb 失败" >&2
  rm -rf "$TMP_ROOT"
  exit 1
fi
assert_deb_has_menu "$ORIG_DEB"

echo "==> deb 宽松 Depends（compat，从原版改 control）"
if grep -q '^Depends:' "$CONTROL"; then
  # 分隔符用 #，Depends 里有 libasound2 | libasound2t64
  sed -i "s#^Depends:.*#${COMPAT_DEPENDS}#" "$CONTROL"
else
  printf '%s\n' "$COMPAT_DEPENDS" >> "$CONTROL"
fi
if [[ -n "$(tail -c1 "$CONTROL" || true)" ]]; then
  printf '\n' >> "$CONTROL"
fi

COMPAT_OUT="$DIST/${BASE}.compat.deb"
if ! rebuild_deb "$TMP_ROOT" "$COMPAT_OUT"; then
  echo "compat dpkg-deb 失败，control 内容：" >&2
  cat "$CONTROL" >&2 || true
  rm -rf "$TMP_ROOT"
  exit 1
fi
rm -rf "$TMP_ROOT"
assert_deb_has_menu "$COMPAT_OUT"

ARCH="${BASE##*_}"
INSTALLER_DEB="$DIST/TextSend-${VERSION}-linux-${ARCH}-installer.deb"
INSTALLER_COMPAT="$DIST/TextSend-${VERSION}-linux-${ARCH}-installer-compat.deb"
mv -f "$ORIG_DEB" "$INSTALLER_DEB"
mv -f "$COMPAT_OUT" "$INSTALLER_COMPAT"

echo "OK  dist/ 下的 .deb"
ls -1 "$DIST"/*.deb
echo "安装包（本机扫库名）：$INSTALLER_DEB"
echo "兼容包（Debian 12/13、Ubuntu）：$INSTALLER_COMPAT"
echo "装一份即可，不要两个一起装（同名包）。"
echo "菜单：/usr/share/applications/textsend.desktop"
echo "CLI：/usr/share/applications/textsend-cli.desktop"
echo "日常：/opt/textsend/bin/TextSend"
echo "CLI：/opt/textsend/bin/TextSend-console（终端跑可看 Log）"
echo "配置：程序目录能写就写旁边；否则主目录 .textsend.properties"
