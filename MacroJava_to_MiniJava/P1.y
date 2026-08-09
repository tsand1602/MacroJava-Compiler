%{
#include <bits/stdc++.h>
#include "P1.tab.h"
using namespace std;
void yyerror(char *s);
int yylex(void);

struct Macro {
    vector<string> params;
    string body;
    bool isExpression;
};

map<string, Macro> macro_table;

string indent(const string &old_str) {
    string new_str;
    bool start = true;
    for (char c : old_str) {
        if (start) 
            new_str += "    ";
        new_str += c;
        start = (c == '\n');
    }
    return new_str;
}

vector<string> splitIdentifierList(const string &str) {
    vector<string> res;
    size_t start = 0, end = 0;
    while ((end = str.find(',', start)) != string::npos) {
        string token = str.substr(start, end - start);
        while (!token.empty() && isspace(token[0])) 
          token.erase(token.begin());
        while (!token.empty() && isspace(token[token.size() - 1])) 
          token.pop_back();
        if (!token.empty()) 
          res.push_back(token);
        start = end + 1;
    }
    string last = str.substr(start);
    while (!last.empty() && isspace(last[0])) 
      last.erase(last.begin());
    while (!last.empty() && isspace(last[last.size() - 1])) 
      last.pop_back();
    if (!last.empty()) 
      res.push_back(last);
    return res;
}

vector<string> splitExpressionList(const string &str) {
    vector<string> res;
    size_t start = 0;
    int depth = 0;
    for (size_t i = 0; i < str.size(); i++) {
        char c = str[i];
        if (c == '(') 
          depth++;
        else if (c == ')') 
          depth--;
        else if (c == ',' && depth == 0) {
            string token = str.substr(start, i - start);
            token.erase(0, token.find_first_not_of(" \t\n\r"));
            token.erase(token.find_last_not_of(" \t\n\r") + 1);
            if (!token.empty()) 
              res.push_back(token);
            start = i + 1;
        }
    }
    string last = str.substr(start);
    last.erase(0, last.find_first_not_of(" \t\n\r"));
    last.erase(last.find_last_not_of(" \t\n\r") + 1);
    if (!last.empty()) 
      res.push_back(last);
    return res;
}

string substituteParams(const Macro &macro, const vector<string> &args) {
    auto isIdentChar = [](char c) {
        return isalnum(static_cast<unsigned char>(c)) || c == '_';
    };
    string withPlaceholders;
    size_t i = 0;
    while (i < macro.body.size()) {
        if (isalpha(static_cast<unsigned char>(macro.body[i])) || macro.body[i] == '_') {
            size_t start = i;
            while (i < macro.body.size() && isIdentChar(macro.body[i])) 
              i++;
            string token = macro.body.substr(start, i - start);

            bool replaced = false;
            for (size_t idx = 0; idx < macro.params.size(); ++idx) {
                if (token == macro.params[idx]) {
                    withPlaceholders += "###" + to_string(idx);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) 
              withPlaceholders += token;
        } else
            withPlaceholders += macro.body[i++];
    }
    string substituted = withPlaceholders;
    for (size_t idx = 0; idx < macro.params.size() && idx < args.size(); ++idx) {
        string placeholder = "###" + to_string(idx);
        string replacement = "(" + args[idx] + ")";
        size_t pos = 0;
        while ((pos = substituted.find(placeholder, pos)) != string::npos) {
            substituted.replace(pos, placeholder.size(), replacement);
            pos += replacement.size();
        }
    }
    for (int iter = 0; iter < 100; ++iter) {
        bool changed = false;
        string expanded;
        size_t pos = 0;
        while (pos < substituted.size()) {
            size_t idStart = substituted.size();
            for (size_t j = pos; j < substituted.size(); ++j) {
                char c = substituted[j];
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_') {
                    idStart = j;
                    break;
                }
            }
            if (idStart == substituted.size()) {
                expanded += substituted.substr(pos);
                break;
            }
            expanded += substituted.substr(pos, idStart - pos);

            size_t idEnd = idStart;
            while (idEnd < substituted.size() && isIdentChar(substituted[idEnd]))
                idEnd++;
            string identifier = substituted.substr(idStart, idEnd - idStart);

            auto it = macro_table.find(identifier);
            size_t nextPos = substituted.find_first_not_of(" \t\r\n", idEnd);
            if (it == macro_table.end() || nextPos == string::npos || substituted[nextPos] != '(') {
                expanded += identifier;
                pos = idEnd;
                continue;
            }

            int parenLevel = 1;
            size_t closingPos = nextPos + 1;
            while (closingPos < substituted.size() && parenLevel > 0) {
                if (substituted[closingPos] == '(') 
                  parenLevel++;
                else if (substituted[closingPos] == ')') 
                  parenLevel--;
                closingPos++;
            }
            if (parenLevel != 0) {
                expanded += identifier;
                pos = idEnd;
                continue;
            }

            string args_str = substituted.substr(nextPos + 1, closingPos - nextPos - 2);
            vector<string> arglist = splitExpressionList(args_str);
            string innerExpansion = substituteParams(it->second, arglist);
            expanded += innerExpansion;
            pos = closingPos;
            changed = true;
        }
        if (!changed) 
          break;
        substituted = expanded;
    }
    return substituted;
}
%}

%union {
    char *val;
}

%token <val> IDENTIFIER INTEGER STRING TRUE FALSE INT BOOLEAN DO LT GT
%token <val> IMPORT IMPORTLIB FUNCTION DEFINE CLASS EXTENDS PUBLIC STATIC VOID
%token <val> WHILE IF ELSE RETURN MAIN AND OR NE LE TO LENGTH PRINT THIS NEW 

%left OR AND
%left NE
%left LT GT LE
%left '+' '-'
%left '*' '/'
%left '[' '.'
%right '!' '='
%nonassoc TO
%nonassoc LOWER_THAN_ELSE
%nonassoc ELSE

%type <val> ImportFunction MacroDefStatement MacroDefExpression MacroDefinition MacroDefList MainClass TypeDeclList 
%type <val> TypeDeclaration VariableDeclaration VariableDeclarations MethodDeclaration MethodDeclarationList Type IdentifierListNonEmpty
%type <val> Statement Statements IdentifierList Expression StatementList
%type <val> ExpList ExpressionList PrimaryExpression NewTypeList CTypeList  

%start Goal

%%

Goal:
      ImportFunction MacroDefList MainClass TypeDeclList
      {
          string res = string($1) + string($2) + string($3) + "\n" + string($4);
          cout << res;
      }
;

ImportFunction:
      /* empty */ 
      { 
          $$ = strdup(""); 
      }
    | IMPORT IMPORTLIB FUNCTION ';'
      {
          string res = string($1) + " " + string($2) + string($3) + ";\n";
          $$ = strdup(res.c_str());
      }
;

MacroDefList:
      /* empty */ 
      { 
          $$ = strdup(""); 
      }
    | MacroDefList MacroDefinition
      {
          string res = string($1);
          if (!res.empty()) 
            res += "\n";
          res += string($2);
          $$ = strdup(res.c_str());
      }
;

MacroDefinition:
      MacroDefStatement
      {
          string res = string($1);
          $$ = strdup(res.c_str());
      }
    | MacroDefExpression
      {
          string res = string($1);
          $$ = strdup(res.c_str());
      }
;

MacroDefStatement:
      '#' DEFINE IDENTIFIER '(' IdentifierList ')' '{' Statements '}'
      {
          Macro m;
          m.params = splitIdentifierList(string($5));
          m.body = string($8);
          m.isExpression = false;
          macro_table[string($3)] = m;
          $$ = strdup("");
      }
;

MacroDefExpression:
      '#' DEFINE IDENTIFIER '(' IdentifierList ')' '(' Expression ')'
      {
          Macro m;
          m.params = splitIdentifierList(string($5));
          m.body = string($8);
          m.isExpression = true;
          macro_table[string($3)] = m;
          $$ = strdup("");
      }
;

MainClass:
      CLASS IDENTIFIER '{' PUBLIC STATIC VOID MAIN '(' STRING '[' ']' IDENTIFIER ')' '{' PRINT '(' Expression ')' ';' '}' '}'
      {
          string res = "class " + string($2) + " {\n    public static void main(String[] " + string($12) + ") {\n        System.out.println(" + string($17) + ");\n    }\n}";
          $$ = strdup(res.c_str());
      }
;

TypeDeclList:
      /* empty */ 
      { 
          $$ = strdup(""); 
      }
    | TypeDeclList TypeDeclaration
      {
          string res = string($1);
          if (!res.empty()) 
            res += "\n";
          res += string($2);
          $$ = strdup(res.c_str());
      }
;

TypeDeclaration:
      CLASS IDENTIFIER '{' VariableDeclarations MethodDeclarationList '}'
      {
          string res = "class " + string($2) + " {\n" + indent(string($4)) + (string($4).empty() || string($5).empty() ? "" : "\n") + indent(string($5)) + "\n}";
          $$ = strdup(res.c_str());
      }
    | CLASS IDENTIFIER EXTENDS IDENTIFIER '{' VariableDeclarations MethodDeclarationList '}'
      {
          string res = "class " + string($2) + " extends " + string($4) + " {\n" + indent(string($6)) + (string($6).empty() || string($7).empty() ? "" : "\n") + indent(string($7)) + "\n}";
          $$ = strdup(res.c_str());
      }
;

MethodDeclarationList:
      /* empty */ 
      { 
          $$ = strdup(""); 
      }
    | MethodDeclarationList MethodDeclaration
      {
          string res = string($1);
          if (!res.empty()) 
            res += "\n";
          res += string($2);
          $$ = strdup(res.c_str());
      }
;

MethodDeclaration:
      PUBLIC Type IDENTIFIER '(' NewTypeList ')' '{' VariableDeclarations StatementList RETURN Expression ';' '}'
      {
          string body = string($8) + "\n" + string($9);
          if (!body.empty()) 
            body = indent(body);
          string ret = indent("return " + string($11) + ";");
          if (!body.empty()) 
            body += "\n" + ret;
          else 
            body = ret;
          string res = "public " + string($2) + " " + string($3) + "(" + string($5) + ") {\n" + body + "\n}";
          $$ = strdup(res.c_str());
      }
;

NewTypeList:
      /* empty */ 
      { 
          $$ = strdup(""); 
      }
    | Type IDENTIFIER CTypeList
      {
          string res = string($1) + " " + string($2) + string($3);
          $$ = strdup(res.c_str());
      }
;

CTypeList:
      /* empty */ 
      { 
          $$ = strdup(""); 
      }
    | CTypeList ',' Type IDENTIFIER
      {
          string res = string($1);
          res += ", ";
          res += string($3) + " " + string($4);
          $$ = strdup(res.c_str());
      }
;

VariableDeclarations:
      /* empty */ 
      { 
          $$ = strdup(""); 
      }
    | VariableDeclarations VariableDeclaration
      {
          string res = string($1);
          if (!res.empty()) 
            res += "\n";
          res += string($2);
          $$ = strdup(res.c_str());
      }
;

VariableDeclaration:
      Type IDENTIFIER ';'
      {
          string res = string($1) + " " + string($2) + ";";
          $$ = strdup(res.c_str());
      }
;

StatementList:
      /* empty */ 
      { 
          $$ = strdup(""); 
      }
    | Statements
      {
          string res = string($1);
          $$ = strdup(res.c_str());
      }
;    

Statements:
      Statement
      { 
          string res = string($1);
          $$ = strdup(res.c_str());
      }
    | Statements Statement
      {
          string res = string($1);
          if (!res.empty()) 
            res += "\n";
          res += string($2);
          $$ = strdup(res.c_str());
      }
;

Statement:
      IF '(' Expression ')' Statement %prec LOWER_THAN_ELSE
      {
          string res = "if (" + string($3) + ")\n" + string($5);
          $$ = strdup(res.c_str());
      }
    | IF '(' Expression ')' Statement ELSE Statement
      {
          string res = "if (" + string($3) + ")\n" + string($5) + "\nelse\n" + string($7);
          $$ = strdup(res.c_str());
      }
    | '{' Statements '}'
      {
          string res = "{\n" + indent(string($2)) + "\n}";
          $$ = strdup(res.c_str());
      }
    | PRINT '(' Expression ')' ';'
      {
          string res = "System.out.println(" + string($3) + ");";
          $$ = strdup(res.c_str());
      }
    | IDENTIFIER '=' Expression ';'
      {
          string res = string($1) + " = " + string($3) + ";";
          $$ = strdup(res.c_str());
      }
    | IDENTIFIER '[' Expression ']' '=' Expression ';'
      {
          string res = string($1) + "[" + string($3) + "] = " + string($6) + ";";
          $$ = strdup(res.c_str());
      }
    | WHILE '(' Expression ')' Statement
      {
          string res = "while (" + string($3) + ")\n" + string($5);
          $$ = strdup(res.c_str());
      }
    | IDENTIFIER '(' ExpList ')' ';'
      {
          string fname = string($1);
          vector<string> args = splitExpressionList(string($3));
          if (macro_table.find(fname) != macro_table.end()) {
              if (!macro_table[fname].isExpression)
                $$ = strdup(substituteParams(macro_table[fname], args).c_str());
              else
                yyerror(strdup(""));
          } else {
              string res = fname + "(" + string($3) + ");";
              $$ = strdup(res.c_str());
          }
      }
;

ExpList:
      /* empty */ 
      { 
          $$ = strdup(""); 
      }
    | ExpressionList
      {
          string res = string($1);
          $$ = strdup(res.c_str());
      }
;

ExpressionList:
      Expression 
      {
          string res = string($1);
          $$ = strdup(res.c_str());
      }
    | ExpressionList ',' Expression
      {
          string res = string($1);
          res += ", ";
          res += string($3);
          $$ = strdup(res.c_str());
      }
;

Expression:
      PrimaryExpression
      {
          $$ = strdup(string($1).c_str());
      }
    | PrimaryExpression OR PrimaryExpression
      {
          string res = string($1) + " || " + string($3);
          $$ = strdup(res.c_str());
      }
    | PrimaryExpression AND PrimaryExpression
      {
          string res = string($1) + " && " + string($3);
          $$ = strdup(res.c_str());
      }
    | PrimaryExpression NE PrimaryExpression
      {
          string res = string($1) + " != " + string($3);
          $$ = strdup(res.c_str());
      }
    | PrimaryExpression LE PrimaryExpression
      {
          string res = string($1) + " <= " + string($3);
          $$ = strdup(res.c_str());
      }
    | PrimaryExpression '+' PrimaryExpression
      {
          string res = string($1) + " + " + string($3);
          $$ = strdup(res.c_str());
      }
    | PrimaryExpression '-' PrimaryExpression
      {
          string res = string($1) + " - " + string($3);
          $$ = strdup(res.c_str());
      }
    | PrimaryExpression '*' PrimaryExpression
      {
          string res = string($1) + " * " + string($3);
          $$ = strdup(res.c_str());
      }
    | PrimaryExpression '/' PrimaryExpression
      {
          string res = string($1) + " / " + string($3);
          $$ = strdup(res.c_str());
      }
    | PrimaryExpression '[' PrimaryExpression ']'
      {
          string res = string($1) + "[" + string($3) + "]";
          $$ = strdup(res.c_str());
      }
    | PrimaryExpression '.' LENGTH
      {
          string res = string($1) + ".length";
          $$ = strdup(res.c_str());
      }
    | PrimaryExpression '.' IDENTIFIER '(' ExpList ')'
      {
          string fname = string($3);
          vector<string> args = splitExpressionList(string($5));
          if (macro_table.find(fname) != macro_table.end()) {
            if (macro_table[fname].isExpression) {
              string res = string($1) + "." + fname + "(" + substituteParams(macro_table[fname], args) + ")";
              $$ = strdup(res.c_str());
            } else
              yyerror(strdup(""));
          } else {
              string res = string($1) + "." + fname + "(" + string($5) + ")";
              $$ = strdup(res.c_str());
          }
      }
    | IDENTIFIER '(' ExpList ')'
      {
          string fname = string($1);
          vector<string> args = splitExpressionList(string($3));
          if (macro_table.find(fname) != macro_table.end()) {
            if (macro_table[fname].isExpression) {
              string res = "(" + substituteParams(macro_table[fname], args) + ")";
              $$ = strdup(res.c_str());
            } else
              yyerror(strdup(""));
          } else {
              string res = fname + "(" + string($3) + ")";
              $$ = strdup(res.c_str());
          }
      }
    | '(' IDENTIFIER ')' TO Expression
      {
          string res = "(" + string($2) + ") -> " + string($5);
          $$ = strdup(res.c_str());
      }
    | IDENTIFIER TO Expression
      {
          string res = string($1) + " -> " + string($3);
          $$ = strdup(res.c_str());
      }
;

PrimaryExpression:
      '!' Expression
      {
          string res = "!" + string($2);
          $$ = strdup(res.c_str());
      }
    | INTEGER
      {
          $$ = strdup(string($1).c_str());
      }
    | TRUE
      {
          $$ = strdup(string($1).c_str());
      }
    | FALSE
      {
          $$ = strdup(string($1).c_str());
      }
    | IDENTIFIER
      {
          $$ = strdup(string($1).c_str());
      }
    | THIS
      {
          $$ = strdup(string($1).c_str());
      }
    | NEW INT '[' Expression ']'
      {
          string res = string($1) + " " + string($2) + "[" + string($4) + "]";
          $$ = strdup(res.c_str());
      }
    | NEW IDENTIFIER '(' ')'
      {
          string res = string($1) + " " + string($2) + "()";
          $$ = strdup(res.c_str());
      }
    | '(' Expression ')'
      {
          string res = "(" + string($2) + ")";
          $$ = strdup(res.c_str());
      }
;

IdentifierList:
      /* empty */ 
      { 
          $$ = strdup(""); 
      }
    | IdentifierListNonEmpty
      {
          string res = string($1);
          $$ = strdup(res.c_str());
      }
;

IdentifierListNonEmpty:
      IDENTIFIER 
      { 
          $$ = strdup(string($1).c_str()); 
      }
    | IdentifierListNonEmpty ',' IDENTIFIER
      {
          string res = string($1);
          if (!res.empty()) 
            res += ", ";
          res += string($3);
          $$ = strdup(res.c_str());
      }
;

Type:
      INT '[' ']'
    {
        $$ = strdup("int[]");
    }
    | BOOLEAN
    {
        $$ = strdup("boolean");
    }
    | INT
    {
        $$ = strdup("int");
    }
    | IDENTIFIER
    {
        string res = string($1);
        $$ = strdup(res.c_str());
    }
    | FUNCTION LT IDENTIFIER ',' IDENTIFIER GT
    {
        string res = string($1) + string($2) + string($3) + ", " + string($5) + string($6);
        $$ = strdup(res.c_str());
    }
;

%%
void yyerror(char *s) {
    printf("// Failed to parse macrojava code.\n");
    exit(1);
}

int main() {
    return yyparse();
}