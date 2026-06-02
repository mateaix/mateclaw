#!/bin/bash

# 获取要扫描的目录
TARGET_DIR="src"

if [ ! -d "$TARGET_DIR" ]; then
    # 如果当前目录下没有 src，尝试找 mateclaw-ui/src (例如从项目根目录执行时)
    if [ -d "mateclaw-ui/src" ]; then
        TARGET_DIR="mateclaw-ui/src"
    else
        echo -e "\033[0;31m[ERROR] 找不到要扫描的 src 目录。\033[0m"
        exit 1
    fi
fi

echo -e "\033[0;32m[INFO] 正在扫描 $TARGET_DIR 下的 Snowflake ID 精度风险...\033[0m"

# 使用 Perl 进行高效且精准的静态代码扫描
perl -e '
use strict;
use warnings;

my $target_dir = $ARGV[0];
my $id_var = qr/\b(?:[a-zA-Z0-9_\$]*Id|[a-zA-Z0-9_\$]*[-_]id|id)\b/;
my $pattern_number = qr/\b(Number|parseInt|parseFloat)\s*\([^)]*$id_var[^)]*\)/;
my $pattern_unary_plus = qr/(?:[=([{?,:!&|]|\breturn)\s*\+\s*[a-zA-Z0-9_\$.]*$id_var/;
my $pattern_typeof = qr/typeof\s+[^=]*$id_var[^=]*===\s*['"']number['"']/;

# 递归寻找目标目录下的 js, ts, vue 文件
my @files;
sub scan_dir {
    my ($dir) = @_;
    opendir(my $dh, $dir) or return;
    while (my $entry = readdir($dh)) {
        next if $entry =~ /^\./;
        my $path = "$dir/$entry";
        if (-d $path) {
            scan_dir($path);
        } elsif (-f $path && $path =~ /\.(ts|js|vue)$/) {
            push @files, $path;
        }
    }
    closedir($dh);
}
scan_dir($target_dir);

my $errors = 0;
for my $file (@files) {
    open(my $fh, "<", $file) or next;
    my $line_num = 0;
    while (my $line = <$fh>) {
        $line_num++;
        next if $line =~ /snowflake-precision-ok/;
        if ($line =~ /$pattern_number/ || $line =~ /$pattern_unary_plus/ || $line =~ /$pattern_typeof/) {
            my $trimmed_line = $line;
            $trimmed_line =~ s/^\s+|\s+$//g;
            print "\033[0;31m[FAIL] $file:$line_num\033[0m\n";
            print "       代码: $trimmed_line\n";
            print "       原因: 将潜在的 Snowflake ID 转换为 number 类型或进行了类型断言，这会导致精度丢失！\n";
            print "       解决: 请使用 String() 处理，或在行尾加上 // snowflake-precision-ok 作为豁免标识。\n\n";
            $errors++;
        }
    }
    close($fh);
}

if ($errors > 0) {
    print "\033[0;31m[ERROR] 扫描完成，共发现 $errors 处 Snowflake ID 精度风险！构建/校验被阻断。\033[0m\n";
    exit 1;
} else {
    print "\033[0;32m[SUCCESS] 扫描完成，未发现 Snowflake ID 精度风险。\033[0m\n";
    exit 0;
}
' "$TARGET_DIR"

exit $?
