//! Lecture d'une ligne de commande, sans rien lancer : programme, simple pipeline ou non, lecture
//! seule ou non, dangereuse ou non. Le dialecte POSIX suit exactement le téléphone
//! (`tools/AgentShell.kt`, `forge/engine/PermissionPolicy.kt`) ; le dialecte PowerShell ajoute les
//! commandes de Windows.

use regex::Regex;
use std::sync::OnceLock;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Dialecte {
    /// sh, bash (Linux, macOS, Git Bash, WSL).
    Posix,
    /// PowerShell (Windows) : noms sans casse, parenthèses et blocs évaluent du code.
    PowerShell,
}

const READ_ONLY: &[&str] = &[
    "ls",
    "cat",
    "head",
    "tail",
    "wc",
    "pwd",
    "echo",
    "grep",
    "egrep",
    "fgrep",
    "find",
    "which",
    "whoami",
    "uname",
    "date",
    "file",
    "stat",
    "du",
    "df",
    "tree",
    "id",
    "hostname",
    "sort",
    "uniq",
    "cut",
    "tr",
    "basename",
    "dirname",
    "realpath",
    "readlink",
    "md5sum",
    "sha1sum",
    "sha256sum",
    "printenv",
    "nproc",
    "free",
    "uptime",
    "ps",
    "jq",
    "diff",
    "cmp",
    "column",
    "nl",
    "true",
];

/// Commandes de lecture propres à PowerShell (en minuscules).
const READ_ONLY_PS: &[&str] = &[
    "get-childitem",
    "gci",
    "dir",
    "ls",
    "get-content",
    "gc",
    "type",
    "cat",
    "get-location",
    "gl",
    "pwd",
    "select-string",
    "sls",
    "findstr",
    "where.exe",
    "get-command",
    "gcm",
    "test-path",
    "get-item",
    "gi",
    "measure-object",
    "select-object",
    "sort-object",
    "get-date",
    "hostname",
    "whoami",
    "get-process",
    "gps",
    "write-output",
    "echo",
    "resolve-path",
    "get-filehash",
    "tree",
    "get-itemproperty",
];

fn read_only_sub(prog: &str) -> Option<&'static [&'static str]> {
    Some(match prog {
        "git" => &[
            "status",
            "log",
            "diff",
            "show",
            "branch",
            "remote",
            "rev-parse",
            "ls-files",
            "blame",
        ],
        "apk" => &["info", "search", "list", "policy"],
        "npm" => &["ls", "list", "view", "outdated"],
        "pip" => &["list", "show", "freeze"],
        _ => return None,
    })
}

const RUNS_PROGRAMS: &[&str] = &[
    "--pre",
    "--compress-program",
    "--use-compress-program",
    "--to-command",
    "--checkpoint-action",
    "--exec",
    "--shell",
];
const SYSTEM_DIRS: &[&str] = &["/bin/", "/usr/bin/", "/sbin/", "/usr/sbin/", "/usr/local/bin/"];
const FIND_WRITES: &[&str] = &[
    "-exec", "-execdir", "-delete", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls",
];
const LIST_FLAGS: &[&str] = &["-a", "-r", "-v", "-vv", "--list", "--all", "--verbose"];

fn mots(s: &str) -> Vec<&str> {
    s.split_whitespace().collect()
}

/// Le programme que nomme un mot, seulement si c'est un nom nu ou un fichier d'un dossier système :
/// `./ls` ou `/root/ls` peuvent être n'importe quoi, ils ne comptent jamais pour « ls ».
pub fn programme_nu(mot: Option<&str>) -> Option<String> {
    let mot = mot?;
    if mot.is_empty() {
        return None;
    }
    if !mot.contains('/') {
        return Some(mot.to_string());
    }
    let coupe = mot.rfind('/')?;
    let dossier = &mot[..=coupe];
    let nom = &mot[coupe + 1..];
    if !nom.is_empty() && SYSTEM_DIRS.contains(&dossier) {
        Some(nom.to_string())
    } else {
        None
    }
}

/// Ni enchaînement, ni redirection, ni arrière-plan, ni substitution : un simple pipeline.
pub fn est_simple(commande: &str) -> bool {
    !commande.trim().is_empty()
        && !commande
            .chars()
            .any(|c| matches!(c, ';' | '&' | '>' | '<' | '`' | '\n'))
        && !commande.contains("$(")
}

pub fn est_simple_pour(commande: &str, d: Dialecte) -> bool {
    est_simple(commande) && (d == Dialecte::Posix || (!commande.contains('(') && !commande.contains('{')))
}

/// Le programme par lequel commence une commande (« npm install x » → « npm »).
pub fn programme(commande: &str) -> Option<String> {
    programme_nu(commande.split_whitespace().next()).filter(|p| est_simple(p))
}

/// Lecture seule, dialecte POSIX (celui du téléphone).
pub fn lecture_seule(commande: &str) -> bool {
    if !est_simple(commande) {
        return false;
    }
    commande.split('|').all(|seg| {
        let w = mots(seg);
        let Some(prog) = programme_nu(w.first().copied()) else {
            return false;
        };
        let args = &w[1.min(w.len())..];
        let prog = prog.as_str();
        if args
            .iter()
            .any(|a| RUNS_PROGRAMS.iter().any(|p| a.starts_with(p)))
        {
            return false;
        }
        if args.iter().any(|a| a.starts_with("--output")) {
            return false;
        }
        if prog == "find" {
            return !args.iter().any(|a| FIND_WRITES.contains(a));
        }
        if prog == "sort" || prog == "tree" {
            return !args.iter().any(|a| a.starts_with("-o"));
        }
        if prog == "uniq" {
            return args.iter().filter(|a| !a.starts_with('-')).count() <= 1;
        }
        if READ_ONLY.contains(&prog) {
            return true;
        }
        if prog == "git" && matches!(args.first().copied(), Some("branch") | Some("remote")) {
            return args[1..].iter().all(|a| LIST_FLAGS.contains(a));
        }
        match (read_only_sub(prog), args.first()) {
            (Some(subs), Some(a)) => subs.contains(a),
            _ => false,
        }
    })
}

/// Lecture seule selon le shell du PC.
pub fn lecture_seule_pour(commande: &str, d: Dialecte) -> bool {
    match d {
        Dialecte::Posix => lecture_seule(commande),
        Dialecte::PowerShell => {
            if !est_simple_pour(commande, d) {
                return false;
            }
            commande.split('|').all(|seg| {
                let w = mots(seg);
                let Some(premier) = w.first() else { return false };
                let prog = premier.to_lowercase();
                let prog = prog
                    .strip_suffix(".exe")
                    .filter(|p| *p != "where")
                    .unwrap_or(&prog)
                    .to_string();
                let args: Vec<String> = w[1..].iter().map(|a| a.to_lowercase()).collect();
                if args
                    .iter()
                    .any(|a| a.starts_with("-outfile") || a.starts_with("-out") || a.starts_with("--output"))
                {
                    return false;
                }
                if READ_ONLY_PS.contains(&prog.as_str()) {
                    return true;
                }
                if prog == "git"
                    && matches!(args.first().map(|s| s.as_str()), Some("branch") | Some("remote"))
                {
                    return args[1..].iter().all(|a| LIST_FLAGS.contains(&a.as_str()));
                }
                match (read_only_sub(&prog), args.first()) {
                    (Some(subs), Some(a)) => subs.contains(&a.as_str()),
                    _ => false,
                }
            })
        }
    }
}

fn dangereuses() -> &'static Vec<Regex> {
    static R: OnceLock<Vec<Regex>> = OnceLock::new();
    R.get_or_init(|| {
        [
            r#"(^|[\s;&|(])rm\s+(-[a-zA-Z]*[rR][a-zA-Z]*\s+|--recursive\s+)+(-[a-zA-Z]+\s+)*("?)(/|~|\*|/\*|/root/?|\.\.|\$HOME)("?)(\s|$|;|&|\|)"#,
            r"(curl|wget)\b[^|;&]*\|\s*(sudo\s+)?(sh|bash|ash|zsh|python3?)\b",
            r"(^|[\s;&|(])sudo\s",
            r"(^|[\s;&|(])mkfs(\.\w+)?\s",
            r"(^|[\s;&|(])dd\s[^;&|]*of=/dev/",
            r":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:",
            r"git\s+push\s+[^;&|]*(--force\b|-f\b)",
            r"chmod\s+(-R\s+)?[0-7]*777\s+/(\s|$)",
            r">\s*/dev/(sd|block|mmc)",
        ]
        .iter()
        .map(|p| Regex::new(p).expect("motif"))
        .collect()
    })
}

fn dangereuses_windows() -> &'static Vec<Regex> {
    static R: OnceLock<Vec<Regex>> = OnceLock::new();
    R.get_or_init(|| {
        [
            // Suppression récursive d'une racine, du profil ou d'un lecteur.
            r#"(?i)\b(remove-item|rm|del|erase|rd|rmdir|ri)\b[^|;&]*(-recurse|-r\b|/s\b)[^|;&]*(\s|"|')([a-z]:\\?|\\|~|\$home|\$env:userprofile|\$env:systemroot|\*)("|')?(\s|$|;|\|)"#,
            r#"(?i)\b(remove-item|rm|del|erase|rd|rmdir|ri)\b\s+("|')?([a-z]:\\?|~|\$home|\$env:userprofile)("|')?\s[^|;&]*(-recurse|-r\b|/s\b)"#,
            // Code téléchargé puis exécuté.
            r"(?i)\b(iwr|irm|invoke-webrequest|invoke-restmethod|curl|wget)\b[^;&]*\|\s*(iex|invoke-expression|sh|bash|powershell|pwsh|cmd)\b",
            r"(?i)\b(iex|invoke-expression)\b",
            r"(?i)\bformat(-volume)?\b\s+(/|-)?[a-z]:?",
            r"(?i)\b(diskpart|bcdedit|cipher\s+/w|reg\s+delete|clear-disk|remove-partition)\b",
            r"(?i)\bstart-process\b[^|;&]*-verb\s+runas",
            r"(?i)\b(stop-computer|restart-computer|shutdown)\b",
            r"(?i)\bset-executionpolicy\b",
        ]
        .iter()
        .map(|p| Regex::new(p).expect("motif"))
        .collect()
    })
}

/// Commandes qui peuvent détruire des données ou lancer du code venu du réseau (règles du téléphone).
pub fn est_dangereuse(commande: &str) -> bool {
    dangereuses().iter().any(|r| r.is_match(commande))
}

/// Les mêmes, plus celles de Windows quand le shell est PowerShell.
pub fn est_dangereuse_pour(commande: &str, d: Dialecte) -> bool {
    est_dangereuse(commande)
        || (d == Dialecte::PowerShell && dangereuses_windows().iter().any(|r| r.is_match(commande)))
}
