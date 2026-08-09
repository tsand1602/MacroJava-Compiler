%{
#include <bits/stdc++.h>
#include "P1.tab.h"
using namespace std;
void yyerror(char *);
%}

%%

"//".*                       { }
"/*"([^*]|\*+[^*/])*\*+"/"   { }


"="             { return '='; }
";"             { return ';'; }
"#"             { return '#'; }

"{"             { return '{'; }
"}"             { return '}'; }
"("             { return '('; }
")"             { return ')'; }
"["             { return '['; }
"]"             { return ']'; }
","             { return ','; }

"define"        { yylval.val = strdup("define"); return DEFINE; }
"class"         { yylval.val = strdup("class"); return CLASS; }
"extends"       { yylval.val = strdup("extends");return EXTENDS; }
"public"        { yylval.val = strdup("public"); return PUBLIC; }
"static"        { yylval.val = strdup("static"); return STATIC; }
"void"          { yylval.val = strdup("void"); return VOID; }
"main"          { yylval.val = strdup("main"); return MAIN; }

"String"        { yylval.val = strdup("String"); return STRING; }
"int"           { yylval.val = strdup("int"); return INT; }
"boolean"       { yylval.val = strdup("boolean"); return BOOLEAN; }


"do"            { yylval.val = strdup("do"); return DO; }
"while"         { yylval.val = strdup("while"); return WHILE; }
"if"            { yylval.val = strdup("if"); return IF; }
"else"          { yylval.val = strdup("else"); return ELSE; }
"length"        { yylval.val = strdup("length"); return LENGTH; }
"return"        { yylval.val = strdup("return"); return RETURN; }

"import"                      { yylval.val = strdup("import"); return IMPORT; }
"Function"                    { yylval.val = strdup("Function"); return FUNCTION;}  
"java\.util\.function\."      { yylval.val = strdup("java.util.function."); return IMPORTLIB; }
"System\.out\.println"        { yylval.val = strdup("System.out.println"); return PRINT; }

"true"          { yylval.val = strdup("true"); return TRUE; }
"false"         { yylval.val = strdup("false"); return FALSE; }
"this"          { yylval.val = strdup("this"); return THIS; }
"new"           { yylval.val = strdup("new"); return NEW; }

"&&"            { yylval.val = strdup("&&"); return AND; }
"||"            { yylval.val = strdup("||"); return OR; }
"!="            { yylval.val = strdup("!="); return NE; }
"<="            { yylval.val = strdup("<="); return LE; }
"->"            { yylval.val = strdup("->"); return TO; }
"."             { yylval.val = strdup("."); return '.'; }
"<"             { yylval.val = strdup("<"); return LT; }
">"             { yylval.val = strdup(">"); return GT; }
"!"             { return '!'; }
"*"             { return '*'; }
"/"             { return '/'; }
"+"             { return '+'; }
"-"             { return '-'; }

0|[1-9][0-9]*   { yylval.val = strdup(yytext); return INTEGER; }

[a-zA-Z_][a-zA-Z0-9_]* { yylval.val = strdup(yytext); return IDENTIFIER; }

[ \t\r\n]+      { }

.               { }

%%

int yywrap(void) {
    return 1;
}