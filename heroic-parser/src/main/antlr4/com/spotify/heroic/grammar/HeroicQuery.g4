/**
 * Define a grammar called Hello
 */
grammar HeroicQuery;

queries
    : (query QuerySeparator)* query EOF
    ;

query
    : Select select From from (Where filter)? (GroupBy groupBy)?
    ;

eqExpr
    : valueExpr Eq valueExpr
    ;

notEqExpr
    : valueExpr NotEq valueExpr
    ;

keyEqExpr
    : SKey Eq valueExpr
    ;

keyNotEqExpr
    : SKey NotEq valueExpr
    ;

hasExpr
    : Plus valueExpr
    ;

prefixExpr
    : valueExpr Prefix valueExpr
    ;

notPrefixExpr
    : valueExpr NotPrefix valueExpr
    ;

regexExpr
    : valueExpr Regex valueExpr
    ;

notInExpr
    : valueExpr Not In valueExpr
    ;

inExpr
    : valueExpr In valueExpr
    ;

notRegexExpr
    : valueExpr NotRegex valueExpr
    ;

notExpr
    : Bang filterExprs
    ;

filterExpr
    : eqExpr
    | notEqExpr
    | keyEqExpr
    | keyNotEqExpr
    | hasExpr
    | prefixExpr
    | notPrefixExpr
    | regexExpr
    | notRegexExpr
    | inExpr
    | notInExpr
    ;

groupExpr
    : LParen filterExprs RParen
    ;

filterExprs
    : filterExpr
    | notExpr
    | groupExpr
    | <assoc=left> filterExprs And filterExprs
    | <assoc=left> filterExprs Or filterExprs
    ;

filter
    : filterExprs
    ;

listValues
    : valueExpr (Colon valueExpr)*
    ;

groupBy
    : listValues
    ;

list
    : LBracket listValues? RBracket
    ;

keyValue
    : Identifier Eq valueExpr
    ;

aggregationArgs
    : listValues (Colon keyValue)*
    | keyValue (Colon keyValue)*
    ;

aggregation
    : Identifier LParen aggregationArgs? RParen
    ;

string
    : QuotedString
    | SimpleString
    | Identifier
    ;

placeholder
    : Placeholder
    ;

value
    : now
    | diff
    | placeholder
    | aggregation
    | list
    | integer
    | string
    ;

diff
    : Diff
    ;

now
    : SNow
    ;

integer: Integer ;

groupValueExpr
    : LParen valueExpr RParen ;

valueExpr
    : value
    | groupValueExpr
    |<assoc=right> valueExpr Plus valueExpr
    |<assoc=right> valueExpr Minus valueExpr
    ;

select
    : All
    | valueExpr
    ;

relative
    : LParen valueExpr RParen
    ;

absolute
    : LParen valueExpr Colon valueExpr RParen ;

sourceRange
    : relative
    | absolute
    ;

from : (SERIES | EVENTS) sourceRange? ;

// lexer below

SERIES : 'series' ;
EVENTS : 'events' ;

// keywords (must come before SimpleString!)
All : 'all' ;

Select : 'select' ;

Where : 'where' ;

GroupBy : 'group by' ;

From : 'from' ;

Or : 'or' ;

And : 'and' ;

Not : 'not' ;

In : 'in' ;

Plus : '+' ;

Minus : '-' ;

Eq : '=' ;

Regex : '~' ;

NotRegex : '!~' ;

Prefix : '^' ;

NotPrefix : '!^' ;

Bang : '!' ;

NotEq : '!=' ;

QuerySeparator : ';' ;

Colon : ',' ;

LParen : '(' ;

RParen : ')' ;

LCurly : '}' ;

RCurly : '}' ;

LBracket : '[' ;

RBracket : ']' ;

Placeholder : LCurly Identifier RCurly ;

QuotedString : '"' StringCharacters? '"' ;

Identifier : [a-zA-Z] [a-zA-Z0-9]* ;

// strings that do not have to be quoted
SimpleString : [a-zA-Z] [a-zA-Z0-9:/_\-\.]* ;

SKey : '$key' ;

SNow : '$now' ;

fragment
Unit
    : 'ms'
    | 's'
    | 'm'
    | 'H'
    | 'd'
    | 'w'
    | 'M'
    | 'y'
    ;

Diff
    : Integer Unit
    ;

Integer
    : '0'
    | [1-9] [0-9]*
    ;

fragment
StringCharacters
    : StringCharacter+
    ;

fragment
StringCharacter
    : ~["\\]
    | EscapeSequence
    ;

fragment
EscapeSequence
    : '\\' [btnfr"'\\]
    ;

WS : [ \t\n]+ -> skip ;

// is used to specifically match string where the end quote is missing
UnterminatedQutoedString : '"' StringCharacters? ;

// match everything else so that we can handle errors in the parser.
ErrorChar : . ;