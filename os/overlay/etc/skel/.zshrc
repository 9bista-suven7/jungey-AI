# Jungey OS zsh. Edit freely: this copy is yours.

# History
HISTFILE=~/.zsh_history
HISTSIZE=20000
SAVEHIST=20000
setopt hist_ignore_dups hist_ignore_space share_history inc_append_history

# Behaviour
setopt autocd interactive_comments no_beep prompt_subst
bindkey -e
bindkey '^[[1;5C' forward-word      # Ctrl+Right
bindkey '^[[1;5D' backward-word     # Ctrl+Left
bindkey '^[[H' beginning-of-line    # Home
bindkey '^[[F' end-of-line          # End
bindkey '^[[3~' delete-char         # Delete

# Completion
autoload -Uz compinit && compinit -d ~/.cache/zcompdump
zstyle ':completion:*' menu select
zstyle ':completion:*' matcher-list 'm:{a-zA-Z}={A-Za-z}'
zstyle ':completion:*' list-colors "${(s.:.)LS_COLORS}"

# Git branch in the prompt
autoload -Uz vcs_info
zstyle ':vcs_info:git:*' formats ' %F{magenta}(%b)%f'
zstyle ':vcs_info:git:*' actionformats ' %F{magenta}(%b|%a)%f'
precmd() { vcs_info }

# Two-line prompt:
# ┌──(you㉿host)-[~/project] (main)
# └─$
PROMPT=$'%F{cyan}┌──(%B%F{white}%n㉿%m%b%F{cyan})-[%B%F{white}%(6~.%-1~/…/%4~.%5~)%b%F{cyan}]%f${vcs_info_msg_0_}\n%F{cyan}└─%B%(#.%F{red}#.%F{cyan}$)%b%f '
RPROMPT='%(?..%F{red}✘ %?%f)'

# Colours and aliases
export LESS='-R'
alias ls='ls --color=auto'
alias ll='ls -lh --group-directories-first'
alias la='ls -lAh --group-directories-first'
alias grep='grep --color=auto'
alias gs='git status -sb'
alias gl='git log --oneline --graph --decorate -20'
alias gd='git diff'
alias vim='nvim'
alias update='sudo apt update && sudo apt upgrade'

# Suggestions from history (→ to accept) and live syntax colours
[ -r /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh ] &&
    source /usr/share/zsh-autosuggestions/zsh-autosuggestions.zsh
ZSH_AUTOSUGGEST_HIGHLIGHT_STYLE='fg=#4b5b6b'
[ -r /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh ] &&
    source /usr/share/zsh-syntax-highlighting/zsh-syntax-highlighting.zsh
