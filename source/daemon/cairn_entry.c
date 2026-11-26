// Entry point for the static daemon binary.
void cairnd_main(long argc, char **argv, char **envp);

__asm__(
    ".text\n"
    ".global _start\n"
    ".type _start, %function\n"
    "_start:\n"
    "    mov  x9, sp\n"          
    "    ldr  x0, [x9]\n"        
    "    add  x1, x9, #8\n"      
    "    add  x2, x0, #1\n"      
    "    add  x2, x1, x2, lsl #3\n" 
    "    bl   cairnd_main\n"
    
    "    mov  x8, #93\n"         
    "    mov  x0, #1\n"
    "    svc  #0\n"
    "1:  b    1b\n"
    ".size _start, .-_start\n"
);
